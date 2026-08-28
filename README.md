# Vision Bank Transfer Approval System

Corporate transfers need a maker-checker control before money moves: a maker submits, and — above a
configurable threshold — one or more checkers must approve before release. This repo builds that as
**two independently deployable Spring Boot services** — the graded deliverable, along with their tests
and this README. The React console and the supporting infrastructure around them are a reviewer
convenience for demonstrating the flow, not graded deliverables in themselves.

## Architecture in one paragraph

**Banking Service** owns what a transfer *means* (submission, validation, release); **Approval Engine**
owns how an approval *progresses* (workflow, policy, quorum, audit, expiry). Neither writes the other's
database; they coordinate only over Redis Streams, each in the direction and delivery guarantee its own
failure mode actually needs. That split isn't just an org chart — it's proven real by a second,
differently-shaped workflow (`privileged-access`, a 3-stage security → manager → compliance review)
running through the same engine with zero code changes, and it's now the live routing target for the
bank's highest-value transfers, not just a test in isolation.

## Repository structure

```text
.
├── approval-engine/          # graded — workflow, policy, quorum, audit, expiry
├── banking-service/          # graded — transfer semantics, release orchestration
├── approval-console-ui/      # reviewer convenience, not graded
├── docs/
│   ├── hld.md                        # full HLD
│   ├── lld.md                        # full LLD
│   ├── transfer-approval-brief.md    # one combined 4-page design doc (not a shortened HLD+LLD)
│   └── transfer-approval-design.html # the architecture story as a single browsable page
├── docker-compose.yml
└── README.md
```

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1 — two independent Gradle services (`approval-engine`, `banking-service`) |
| Data | PostgreSQL 16 (one database per service, no shared schema) |
| Messaging | Redis 7 Streams (`approval-engine` ↔ `banking-service`, at-least-once, consumer groups) |
| Frontend | React 19 + MUI, built with Vite/TypeScript, served as a static SPA via nginx |
| Orchestration | Docker Compose — 5 containers: postgres, redis, approval-engine, banking-service, approval-console-ui (2 application services; the rest is supporting infrastructure) |

## Requirements to run

Only **Docker Desktop (or Docker Engine + the Compose plugin)** — nothing else needs to be installed on
your host. Each service builds inside its own container (Java 21 toolchain, Node 20 for the UI build),
so you don't need Java or Node locally unless you want to run a service's tests directly.

- Free ports: `3000` (console UI), `8080` (banking-service), `8081` (approval-engine), `5432` (postgres),
  `6379` (redis).
- A couple of GB free disk for images/volumes on first build.

## Run it

```bash
docker compose up --build
```

If you have an existing local Postgres volume from before, run `docker compose down -v` first — this
plan added new required columns and widened a unique constraint that `ddl-auto: update` can't safely
apply to existing data.

Open **http://localhost:3000** — the Approval Console UI. That's the one URL you need; see the demo
guide below for a walkthrough.

Run each service's tests independently:
```bash
cd approval-engine && ./gradlew test
cd ../banking-service && ./gradlew test
```

## Demo guide

The console's top nav has an **actor picker** — it switches which maker or checker identity your
browser acts as, standing in for real login. Amount decides which path a transfer takes:

1. **Auto-release (< AED 5,000).** Start as a maker, submit a transfer. It releases with zero approvals
   — watch its state go `CREATED → RELEASED` in a couple of seconds, no checker involved.
2. **Single checker (AED 5,000–50,000).** Submit one in this range — it lands on `PENDING_APPROVAL`.
   Switch the actor picker to a checker identity and approve it → `RELEASED`.
3. **Dual control (AED 50,000–100,000).** Submit one here — it needs 2 checkers. Approve as checker A:
   notice your own approve/reject buttons disappear and the card shows "waiting on 1 more" (the server
   would `409` a second decision from the same actor anyway). Switch to checker B and approve →
   `RELEASED`.
4. **Privileged access (≥ AED 100,000).** This tier skips the transfer workflow entirely and routes into
   a 3-stage review — security (2 approvals), then manager (1), then compliance (1) — each stage its
   own role in the actor picker. Walk it stage by stage the same way.
5. **Reject / cancel** at any pending stage instead of approving, to see the other terminal states —
   cancel is only available to the maker who submitted it, and only on the transfer-shaped workflows
   (privileged-access has no cancel path at all).

> **Architecture proof — second workflow, zero engine changes.** Step 4 is the one to watch closely:
> `privileged-access:v2` runs `SECURITY_REVIEW → MANAGER_APPROVAL → COMPLIANCE_REVIEW → APPROVED` through
> the *same* Approval Engine, with no engine code written for it — only a YAML file and a `policy_rule`
> row. That's the domain-independence claim made concrete, not asserted.

## Key design decisions

**The ownership split is the whole design.** Two independent Spring Boot apps, one Postgres DB each.
Transfer semantics live in Banking; workflow/policy/quorum/audit live in Approval Engine. Everything
else below is a consequence of keeping that boundary honest.

**Sync where a human is waiting, async where uptime shouldn't be coupled.** Client → Banking stays
synchronous REST — someone's waiting for "did it submit." Banking ↔ Approval Engine runs over two
one-direction Redis Streams (`stream:transfer-approval-create`, `stream:approval-lifecycle-events`),
each with one consumer group, at-least-once (a message stays pending until acknowledged, reclaimable via
`XPENDING`+`XCLAIM` after 30s idle). `POST /transfers` returns `CREATED` immediately; the workflow link
happens moments later. Neither service's uptime gates the other's — this only works because every
consumer was already idempotent before Redis entered the picture: `ApprovalCommandService.create()` by
`Idempotency-Key` + body hash, `ApprovalEventListener.handle()` by `processed_event.event_id`.

**Every race resolves through one mechanism, not one per case.** Two checkers approving at once, a
maker cancelling while a checker approves, an SLA sweep racing an approval — all of it goes through a
single guarded conditional `UPDATE ... WHERE state = ? AND version = ?`. Exactly one caller's row count
comes back 1 and wins; every other caller's entire transaction (decision, audit, outbox) rolls back and
is classified as a clean `409`, never a partial write. Quorum *counting* additionally takes a short
`PESSIMISTIC_WRITE` row lock — the one deliberate exception — since tallying committed votes is an
aggregate read the guarded `UPDATE` alone can't protect.

**Policy chooses the workflow; the workflow defines how it executes.** `policy_rule(min, max,
workflowId, workflowVersion)` is the only thing Banking's transfer amount ever touches — it has no
opinion on stages, roles, or quorum. Everything below that line is the resolved workflow's own YAML:

```text
Transfer amount → Approval Policy → workflowId:version → Workflow definition (states, roles, quorum)

  < AED 5,000        → transfer-auto-release:1
  AED 5,000–50,000    → transfer-single-checker:1
  AED 50,000–100,000  → transfer-high-value:1
  ≥ AED 100,000        → privileged-access:2
```

That separation is what let `privileged-access` go live as the ≥AED 100,000 tier — and let it version
from `v1` (1 security approval) to `v2` (2) — without touching engine code or migrating in-flight
requests, since each request's `policy_snapshot` freezes the exact version it was created against.

**Everything unproven is stubbed behind an interface, not faked inline.** `CoreBankingClient` stands in
for real settlement (per the assignment's explicit allowance); `NotificationClient` stands in for real
email/SMS (`LoggingNotificationClient` just logs — wired on `ApprovalRejected`/`ApprovalExpired`, not
`ApprovalCancelled`, since the maker caused that one themselves). Swapping either for a real
implementation is a one-class change behind the same interface.

**The SLA is compressed for demo purposes.** `transfer.approval-sla-seconds` defaults to 300s (5
minutes) here, not the assignment's illustrative 24 hours, so expiry is observable in a short session.
`ExpirySweeper` — which lives in Approval Engine, not Banking — polls every 60s, comfortably inside
that window.

## Where to inspect the implementation

| Looking for… | Start here |
|---|---|
| Workflow definitions (states, roles, quorum) | `approval-engine/src/main/resources/workflow/definitions/*.yaml` |
| Policy rules (amount → workflow) | `approval-engine/src/main/java/.../policy/` (`PolicyRuleSeeder`, `PolicyRuleResolutionService`) |
| The generic transition engine | `approval-engine/src/main/java/.../service/ApprovalCommandService.java` |
| Concurrency proofs | `approval-engine/src/test/java/.../service/ApprovalConcurrencyTest.java`, `ExpiryVersusApproveConcurrencyTest.java`, `PrivilegedAccessConcurrencyTest.java` |
| Transfer lifecycle & release | `banking-service/src/main/java/.../domain/Transfer*.java`, `service/ReleaseService.java` |
| Cross-service event handling | `banking-service/src/main/java/.../approval/ApprovalEventListener.java` |
| UI | `approval-console-ui/src/` |

## What I'd do differently with more time

Ordered by impact — the gaps most likely to cost real money or break the control first, polish last:

1. **A funds hold at submission** (`CoreBankingClient.hold(...)`, consumed on release, freed on
   reject/cancel/expire) — without it, two large concurrent transfers against the same account can each
   pass validation independently and both later release, overspending the real balance. The one gap
   named deliberately rather than silently omitted.
2. **Real authentication** in place of trusting `X-Actor-Id`/`X-Actor-Role` headers — maker-checker
   segregation is only as strong as the identity behind it, and today that identity is caller-asserted,
   not verified. OIDC/JWT at the gateway, role claims checked server-side, closes that hole.
3. **A dead-letter queue** for messages past max delivery attempts — today those just get logged loudly
   and dropped, so an approval or release event could silently stall with no operational trail.
4. **A retry scheduler for `RELEASE_PENDING`** — the state already exists for a stuck release, but
   nothing polls it; it's only safe today because the Core Banking stub never actually fails.
5. **Flyway migrations** instead of `ddl-auto: update`, plus transactional error handling in the
   docker-compose Postgres init script (today a failed statement partway through continues silently
   instead of rolling back or failing loud).
6. **Rate limiting** per-maker/per-actor at the gateway — distinct from idempotency, which guards
   correctness, not volume; a runaway retry loop today isn't actually throttled.
7. **Real notification delivery** behind `NotificationClient` instead of the logging stub.
8. **A reporting UI over the audit trail** — transfers by state/tier/maker, SLA breaches, per-checker
   history — turning `audit_log` into an operational surface, not just a write-only log.
9. **A workflow authoring UI** — edit stages, roles, quorum, and `policy_rule` ranges as data, instead of
   hand-editing YAML for a new tier.
10. **A finer-grained concurrency primitive than `PESSIMISTIC_WRITE`** — already correct at this scale,
    but a monotonic per-stage approval counter or an advisory lock scoped to `request_id` would shrink
    the held critical section under real load.
11. **A priority queue** for high-value/`privileged-access` approvals, so time-sensitive money isn't
    stuck behind a backlog of low-tier auto-releases.
12. **Physically separate Postgres instances** for the two databases, so "neither service writes the
    other's DB" is enforced by infrastructure, not just convention.

One documented behavior worth flagging rather than fixing: retrying with the same actor *after their own
decision already completed quorum* returns `409 CONCURRENT_STATE_CHANGE` rather than replaying the
decided state (the terminal-state check runs first) — defensible REST behavior, but narrower than the
idempotency promise as first written, so it's called out here rather than left for a reviewer to
discover.

## Full design record

`docs/hld.md` / `docs/lld.md` — full HLD/LLD. `docs/transfer-approval-brief.md` — one combined 4-page
Architecture & Design document, not a shortened HLD followed by a shortened LLD.
`docs/transfer-approval-design.html` — the architecture story as a single browsable page, sitting
between the 4-page brief and the full HLD/LLD.
