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
roles) to see both sides of the approval flow.

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

Amounts under 5,000.00 auto-release; 5,000–50,000 need 1 checker; 50,000+
need 2. These are rows in Approval Engine's `policy_rule` table (seeded from
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
  changes, just a new YAML file) is now built and verified end-to-end, not
  just asserted from one tenant. See docs/hld.md and the design spec for how
  it works.
- Error handling and rollback in the docker-compose Postgres init script
  (`docker-compose-postgres-init/01-init.sql`) — currently if a statement
  fails partway through (e.g., creating the `approval` role/database fails
  after `banking`'s succeeded), the script continues silently without rolling
  back or failing loud, risking an inconsistent setup on partial success.
- The idempotency promise in the design doc was narrower in practice than first written: retrying with the same actor after *their own* decision completed quorum returns `409 CONCURRENT_STATE_CHANGE` rather than replaying the decided state (the terminal-state check runs first) — defensible REST behavior, but worth a documented exception rather than a broader promise.
