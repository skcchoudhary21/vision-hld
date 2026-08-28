# Vision Bank Transfer Approval System

Maker-checker approval workflow for domestic fund transfers, built as two
independently deployable Spring Boot services. Full design record:
`docs/superpowers/specs/2026-08-25-transfer-approval-design.md`. Submission
HLD/LLD: `docs/hld.md`, `docs/lld.md`.

## Run it

```bash
docker compose up --build
```

If you have an existing local Postgres volume from before, run `docker
compose down -v` first — this plan added new required columns and widened a
unique constraint that `ddl-auto: update` can't safely apply to existing data.

**Approval Console UI: http://localhost:3000** — this is the single URL to open;
it's a React SPA (`approval-console-ui/`) served by nginx, backed by Banking
Service (http://localhost:8080) and Approval Engine (http://localhost:8081).
Switch identities via the actor picker in the top nav (maker vs. checker
roles) to see both sides of the approval flow. Once you approve a stage that
needs more than one checker, your own approve/reject buttons disappear
(the server would 409 on a second decision from the same actor+stage
anyway) and the checker view shows how many more approvals are still
needed — switch to another checker identity in the actor picker to cast
the next vote.

Submit a transfer directly against the API instead:
```bash
curl -X POST http://localhost:8080/transfers \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -H "X-Actor-Id: maker-1" -H "X-Actor-Role: MAKER" \
  -d '{"makerId":"maker-1","fromAccount":"ACC-FUNDED","toAccount":"ACC-DEST","amountMinorUnits":100000,"currency":"AED"}'
```
Expected immediate response: `{"transferId": "...", "state": "CREATED"}` —
submission is asynchronous now, so poll `GET /transfers/{id}` (with the same
`X-Actor-Id`/`X-Actor-Role` headers) to observe the subsequent state
(`PENDING_APPROVAL` or `RELEASED`), typically within a couple seconds.

Amounts under 5,000.00 auto-release; 5,000–50,000 need 1 checker;
50,000–100,000 need 2 checkers; 100,000+ escalates out of the transfer
workflow entirely into a 3-stage `privileged-access` review (2 security
officers, then 1 manager, then 1 compliance officer) — same engine, same
policy table, just a different workflow definition and version. These
tiers are rows in Approval Engine's `policy_rule` table (seeded from
`approval-engine`'s `application.yml`, editable at runtime via
`PUT /policy-rules`), not a hardcoded rule in Banking Service. Resolution now
happens in-process inside approval-engine's `SubmissionCommandConsumer` (via
`PolicyRuleResolutionService`) when it consumes the creation command off the
Redis stream — not a synchronous call out of Banking Service.

Run each service's tests independently:
```bash
cd approval-engine && ./gradlew test
cd banking-service && ./gradlew test
```

## Key design decisions

- Two independent Spring Boot apps (Java 21, Spring Boot 4.1.x), one Postgres
  DB each — Transfer Domain owns what a transfer means, Approval Engine owns
  how an approval progresses.
- Submission (banking-service → approval-engine) and lifecycle notification
  (approval-engine → banking-service) both go through Redis Streams
  (`stream:transfer-approval-create`, `stream:approval-lifecycle-events`),
  each with one consumer group. `POST /transfers` now returns `CREATED`
  immediately — the workflow link happens asynchronously, typically within
  a couple seconds. At-least-once delivery (a message stays pending until
  acknowledged, reclaimable via `XPENDING` + `XCLAIM` after 30s) is safe here
  because every consumer is already idempotent: `ApprovalCommandService.
  create()` by `Idempotency-Key`+body-hash, `ApprovalEventListener.handle()`
  by `processed_event.event_id`.
- Every competing state transition — two checkers approving at once, a maker
  cancelling while a checker approves, an SLA expiry racing an approval —
  goes through one mechanism: a single guarded conditional `UPDATE ... WHERE
  state = ? AND version = ?`. Exactly one caller wins; the loser's entire
  transaction (decision, audit, outbox) rolls back.
- Core Banking is stubbed behind a `CoreBankingClient` interface inside
  Banking Service, per the assignment's explicit allowance — not a third
  deployable service.
- Maker notification (approval outcome, expiry) is a `NotificationClient`
  interface, same pattern as Core Banking; `LoggingNotificationClient` logs
  instead of sending email/SMS, per the assignment's allowance to mock
  notifications. Wired on `ApprovalRejected`/`ApprovalExpired` (not
  `ApprovalCancelled` — the maker caused that one themselves).
- The approval SLA (`transfer.approval-sla-seconds`, banking-service
  `application.yml`) defaults to 300s (5 minutes) here, not the assignment's
  illustrative 24 hours, so expiry is observable in a short demo session —
  `ExpirySweeper` already polls every 60s, comfortably inside that window.

## What I'd do differently with more time

- A funds hold at submission (`CoreBankingClient.hold(...)`), consumed on
  release and freed on reject/cancel/expire — without it, two large
  concurrent transfers against the same account can each pass validation
  independently and both later release, overspending the real balance.
  Named here deliberately rather than silently omitted.
- Flyway migrations instead of `ddl-auto: update`.
- A retry scheduler polling `RELEASE_PENDING` transfers for core-banking
  failures (the state exists; the poller doesn't, since the stub never fails).
- Real authentication instead of trusting `actorId`/`actorRole` in request bodies.
- Real email/SMS delivery behind `NotificationClient` instead of the logging stub.
- The multi-workflow generalization (a second, differently-shaped workflow —
  privileged-access — genuinely running through the same engine, no code
  changes, just a new YAML file) is no longer just verified end-to-end in
  isolation: it's now wired as the live `policy_rule` tier for transfers
  ≥ AED 100,000, so workflow versioning (v1 → v2, quorum 1 → 2 on the
  security stage) is exercised by real submissions, not only by
  `PrivilegedAccessConcurrencyTest`. See docs/hld.md and the design spec for
  how it works.
- Error handling and rollback in the docker-compose Postgres init script
  (`docker-compose-postgres-init/01-init.sql`) — currently if a statement
  fails partway through (e.g., creating the `approval` role/database fails
  after `banking`'s succeeded), the script continues silently without rolling
  back or failing loud, risking an inconsistent setup on partial success.
- The idempotency promise in the design doc was narrower in practice than first written: retrying with the same actor after *their own* decision completed quorum returns `409 CONCURRENT_STATE_CHANGE` rather than replaying the decided state (the terminal-state check runs first) — defensible REST behavior, but worth a documented exception rather than a broader promise.
- A reporting UI over the audit trail — transfers by state/tier/maker, SLA breaches, per-checker decision history — turning `audit_log` into an operational and compliance surface, not just a write-only log.
- A workflow create/edit UI — a screen to author and version workflow definitions (stages, transitions, `allowedRoles`, `requiredApprovals`) and edit `policy_rule` ranges, so tiers change without hand-editing YAML; the engine already treats both as data.
- A less coarse concurrency primitive than `PESSIMISTIC_WRITE` — correct at this scale, but under real load a monotonic per-stage approval counter with a conditional guarded write, or an advisory lock scoped to `request_id`, would shrink the held critical section.
- Rate limiting per-maker and per-actor at the gateway (token-bucket) — distinct from idempotency, which handles correctness, not volume, so a runaway retry loop today isn't actually throttled.
- A priority queue/consumer group for high-value and privileged-access approvals, so they aren't stuck behind a backlog of low-tier auto-releases when time-sensitive money should move first.
- A dead-letter queue for messages past max delivery attempts, instead of logging loudly and moving on.
- Physically separate Postgres instances for the two databases, so "neither service writes the other's DB" is enforced by infrastructure, not just convention.
