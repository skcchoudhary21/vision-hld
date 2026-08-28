# High-Level Design — Transfer Approval System

## 1. Problem & Approach

Vision Bank's corporate customers submit domestic fund transfers that must clear a maker-checker
control before release: a maker submits, the system validates the request, and — above a
configurable threshold — one or more checkers must approve before money moves. Getting this
wrong in either direction is expensive: too little control and a single compromised or careless
user can move money; too much control (or a fragile approval pipeline) and legitimate transfers
stall or get double-released during a race.

The design answers this with **two independently deployable services, split along a hard
ownership line**: **Banking Service owns what a transfer *means*** (submission, validation,
release orchestration); **Approval Engine owns how an approval *progresses*** (workflow catalog,
policy rules, state, concurrency, audit, expiry). Neither service writes the other's database.
They coordinate over a network boundary — not in-process calls, per the assignment's constraint
— using the pattern each interaction actually needs (see §4). This split means the approval
*mechanism* (quorum, race-safety, audit) is reusable for **any** approval-worthy domain, not just
transfers — proven out in §7.

## 2. Context Diagram & Deployment

What `docker-compose.yml` runs, one command: one Postgres container (two databases, `approval`
and `transfer`), one Redis container, and the two Spring Boot services.

![alt text](hld.drawio.svg)




The only real external dependency is Core Banking, and it's stubbed in-process (`CoreBankingClient`)
per the assignment's explicit allowance — not a third deployable service. The Approval Console UI
is a reviewer convenience, not a graded deliverable: it talks only to Banking Service, which
proxies engine reads under `/ui-api/**`, so it introduces no new service-to-service contract.

Production would add an API Gateway/WAF, load balancing, and N horizontally-scaled instances per
service. Omitted here deliberately — none of it changes the ownership or consistency model below,
which is what this exercise is actually testing.

## 3. Service Responsibilities & Data Ownership

| Concern | Owner |
|---|---|
| Transfer semantics, validation, duplicate detection | Banking |
| Policy rules (amount range → workflow) + resolution | Approval Engine |
| Policy snapshot persistence | Approval Engine |
| Workflow state, guards, concurrency | Approval Engine |
| Audit, SLA expiry, outbox | Approval Engine |
| Release orchestration + release idempotency | Banking |
| Balance/limit authority, money movement | Core Banking (stub) |

`Core Banking (stub)` is a further-back, stubbed ledger/settlement dependency — not the `Banking
Service` above it; the naming deliberately mirrors the real digital-banking-in-front-of-core-banking
pattern, so the boundary reads the same way it would in production.

Policy is data, not code: it lives entirely in Approval Engine as an editable `policy_rule` table
(amount range → `workflowId`/`workflowVersion`), resolved in-process inside
`SubmissionCommandConsumer` (via `PolicyRuleResolutionService`) when it consumes the transfer's
creation command off the Redis stream. Banking Service does not call out to resolve it anymore
(one dead-code caveat this leaves behind — see LLD's Policy Contract). Required-approvals count and
eligible role aren't a separate policy object on the wire; they're attributes of the resolved
workflow's own `approve` transition — one source of truth, in the service that already owns the
workflow catalog those rules route to.

## 4. Communication Pattern — Sync vs. Async, and Why

Each hop uses the pattern its own failure mode demands, not a single style applied uniformly:

- **Client → Banking: synchronous REST.** A human is waiting on the other end for a real answer
  ("did my transfer submit") — blocking here is the point. If Banking is down, the request simply
  fails fast and is retryable; there's no partial state to reconcile.
- **Banking ↔ Approval Engine: asynchronous, via two single-direction Redis Streams**
  (`stream:transfer-approval-create` and `stream:approval-lifecycle-events`), each with one
  consumer group. Not because the two services can't reach each other over REST — because neither
  service's *uptime* should gate the other's. `POST /transfers` must not hang because the engine
  is slow or unreachable; the engine's approve/reject/cancel handling must not hang because
  Banking is slow or unreachable. Redis Streams buys durable, at-least-once, redeliverable
  delivery in both directions for the cost of one already-required property: consumer
  idempotency (see §5) — no new failure mode introduced by choosing async.
- **Banking → Core Banking (release): synchronous REST, idempotent by `transferId`.** Release is
  the one place a genuinely synchronous answer matters ("did the money move"); if Core Banking is
  unavailable, the transfer simply holds at `RELEASE_PENDING` and is retried with the same ID —
  safe because the call is idempotent, so a retry can never double-release.

| Flow | Pattern | If unavailable |
|---|---|---|
| Client → Banking | REST sync | Fails fast, retryable |
| Banking → Engine (submission) | Redis Stream, at-least-once | Message persists in Redis; `POST /transfers` never blocks on Engine's availability |
| Engine → Banking (lifecycle events) | Redis Stream, at-least-once | Message persists in Redis; reclaimed via `XPENDING` + `XCLAIM` if a consumer crashes mid-handling |
| Banking → Core Banking (release) | REST sync, idempotent by `transferId` | Stays `RELEASE_PENDING`, retried |

## 5. Key Non-Functional Requirements

**Idempotency of transfer submission.** `POST /transfers` requires an `Idempotency-Key`; a retry
with the same key and body replays the original result, a retry with the same key and a different
body gets `409 IDEMPOTENCY_CONFLICT` rather than silently doing the wrong thing. The same shape
protects `POST /approvals` (create). Decisions (approve/reject/cancel) are idempotent per
`(request_id, actor_id, state)` instead of a header, since a checker's *intent* — "I approve this
stage" — is the natural idempotency key there, not an opaque token they'd have to generate.
Release is idempotent on `transferId`. None of this is decorative: it's what makes Redis's
at-least-once redelivery safe to introduce in §4 without inventing a second correctness mechanism.

**Consistency model between the two services.** Strong and transactional *within* each service
(one local ACID transaction per state change); **eventually consistent across the boundary** — no
distributed transaction is attempted, and the outbox/stream pairing is the seam that makes that
safe. Resilience is symmetric in both directions now that submission and lifecycle notification
both go through Redis Streams: Engine being down does not break `submit()` — a transfer always
reaches `CREATED` immediately, and the creation command retries against Redis until Engine comes
back, giving up only after `SubmissionCommandReconciler`'s delivery-attempt ceiling (LLD's Redis
Stream Delivery section), at which point the transfer moves to `FAILED` — itself resumable, not
terminal — and the maker is notified. Banking being down likewise does not break approve/reject/
cancel: the engine's state machine and audit trail never depend on Banking's reachability, only
lifecycle-event *delivery* does, and that persists in Redis until Banking comes back.

**Partial failure.** State, audit, and outbox commit atomically in one local transaction; delivery
across the boundary is a separate, retried step. A crash between "decision recorded" and "event
delivered" never loses the decision — it only delays the counterparty finding out, and the
reconciler on each side closes that gap automatically.

**Concurrency** (full mechanism and tests in the LLD's Concurrency section): every competing
transition — two checkers approving at once, a maker cancelling while a checker approves, an SLA
sweep racing an approval — resolves through one guarded conditional `UPDATE`, plus a
quorum-counting row lock where more than one approval must be tallied. Exactly one caller wins;
every other party's entire transaction rolls back and is classified as a clean 409, never a
partial write.

## 6. Trade-offs & Named Gaps

The rubric asks not just what was built, but what was deliberately left out and why. In order of
how much it should worry a reviewer:

**No funds hold between validation and release (the one gap I would not want a reviewer to have
to find themselves).** Balance is checked once at submission, not reserved until release, so two
large concurrent transfers against the same account can each pass validation independently and
later both release, overspending the real balance. The correct fix is a
`CoreBankingClient.hold()`/`free()` protocol — a second stateful contract with Core Banking beyond
what this exercise's time budget covers. Every other race in this system (§5) is closed; this one
is named, not silently absent.

**Maker notification is a stub, not a real channel.** `ExpirySweeper` transitions an un-actioned
request to `EXPIRED` and emits `ApprovalExpired`; Banking Service's `ApprovalEventListener`
consumes it, sets the transfer to `EXPIRED`, and calls `NotificationClient.notifyMaker(...)` —
also wired on `ApprovalRejected` and `ApprovalCreationFailed` (not `ApprovalCancelled`: the maker
caused that one themselves).
Per the assignment's explicit allowance, `LoggingNotificationClient` only logs; swapping in real
email/SMS is a one-class change behind the same interface.

**Deliberate architectural choices, not gaps:**
- *YAML workflow definitions over a hardcoded state machine* — transitions change without a
  redeploy; no rule engine was built around it, since one wasn't needed for four workflow shapes.
- *DB outbox (engine → banking direction only) feeding Redis Streams* — not outbox *instead of* a
  broker, outbox *in front of* one. The outbox is the local crash-safe claim seam
  (`OutboxClaimService`) that survives a crash before a lifecycle event ever reaches Redis;
  submission (banking → engine) publishes straight to Redis after persisting, no outbox needed,
  since there's nothing else in that write left to claim. Redis Streams itself, over a heavier
  managed broker (Kafka/SQS/RabbitMQ): right-sized for this volume, one moving part instead of
  three.
- *Core Banking as a stubbed in-process interface, not a third service* — explicitly allowed by
  the assignment, and modeling it as a real service would have spent time proving nothing new
  about this design's actual hard problem (the approval boundary).
- *Seams over generalization machinery* — the engine exposes exactly three seams (workflow YAML,
  policy snapshot, opaque payload) to prove domain-independence, and stops there: no tenant
  registry, no plugin framework, no workflow designer UI. §7 shows the seam is real without
  building infrastructure nobody asked for.

## 7. Extensibility — Built, Verified, and Now Live

A second workflow-driven domain, `privileged-access` — a 3-stage security → manager → compliance
review, each stage with its own role and quorum — runs through the *same* engine with **zero
engine code changes**: just a new YAML file and a `policy_rule` row; the engine never inspects the
opaque JSONB `payload` it carries. It started as an isolated proof this boundary is real
(`PrivilegedAccessConcurrencyTest`); it is now also the live routing target for the highest
transfer tier (≥ AED 100,000 — see LLD's Policy Contract), so a real submission exercises it, not
just a test. That also means the same mechanism proving domain-independence proves workflow
*versioning* under real traffic for free: `v1` required 1 `SECURITY_CHECKER`, `v2` (what transfers
now route to) requires 2 — no engine change, and no migration of any in-flight `v1` request, since
`policy_snapshot` freezes the exact version a request was created against.

## 8. Assumptions & Out of Scope

Stated explicitly, as the assignment asks, rather than left for a reviewer to infer:

| In scope | Out of scope |
|---|---|
| Two services, Postgres, Redis Streams, docker-compose | A managed/clustered broker (Kafka, MSK, Redis Cluster), real core banking, real auth |
| REST sync commands, outbox + stream async events | Multi-region, multi-currency, delegation |
| OCC + row-lock concurrency, audit, expiry | Tenant registry, BPMN/Temporal/Camunda |
| Idempotent submission/create/release | Funds hold/reservation (named gap, §6) |
| Editable policy rules, versioned workflows | — |
| A second workflow domain (privileged-access), now also the live ≥ AED 100,000 tier | — |

A demo React console (`approval-console-ui`) is included for reviewer convenience but isn't a
graded deliverable — see §2. It adds no authentication of its own: it forwards the same trusted
`X-Actor-Id`/`X-Actor-Role` headers the API already accepts, standing in for real auth (out of
scope per the table above).
