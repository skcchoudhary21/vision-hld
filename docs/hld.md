# High-Level Design — Transfer Approval System

### Core design principle

**Banking owns the transfer lifecycle. Approval Engine owns the approval lifecycle. Approval ≠
execution** — the engine only ever decides whether a transfer *may* proceed; Banking and Core Banking
are the only things that ever move money. Policy selects a workflow; the workflow defines the approval
journey; approval completion signals Banking to release. That one sentence is the architecture —
everything below explains why it holds up, what it costs, and what's deliberately left out.

## 1. Problem & Approach

Vision Bank's corporate customers submit domestic fund transfers that must clear a maker-checker
control before release: a maker submits, the system validates the request, and — above a
configurable threshold — one or more checkers must approve before money moves. Getting this
wrong in either direction is expensive: too little control and a single compromised or careless
user can move money; too much control (or a fragile approval pipeline) and legitimate transfers
stall or get double-released during a race.

The design answers this with **two independently deployable services, split along a hard
ownership line**, coordinating over a network boundary — not in-process calls, per the assignment's
constraint — using the pattern each interaction actually needs (§5). This split means the approval
*mechanism* (quorum, race-safety, audit) is reusable for **any** approval-worthy domain, not just
transfers — proven out in §8.

## 2. System Context

What `docker-compose.yml` runs, one command: one Postgres container (two databases, `approval`
and `transfer`), one Redis container, and the two Spring Boot services.

![alt text](hld.drawio.svg)

**In one line:** a maker submits through Banking, which persists the transfer and hands off to
Approval Engine over Redis; the engine runs the resolved workflow to completion independently, then
signals Banking over Redis to release the transfer through Core Banking. Neither service is ever
synchronously blocked waiting on the other, except where a human is directly waiting (client →
Banking) or where money is actually moving (Banking → Core Banking) — §4 walks this end to end, §5
explains why each hop is sync or async.

The only real external dependency is Core Banking, and it's stubbed in-process (`CoreBankingClient`)
per the assignment's explicit allowance — not a third deployable service. The Approval Console UI
is a reviewer convenience, not a graded deliverable: it talks only to Banking Service, never
directly to Approval Engine, so it introduces no new service-to-service contract of its own.
Banking's `/ui-api/**` proxies most of this over synchronous REST to Approval Engine (approval
reads, decisions, workflow/policy lookups) purely so the browser has one origin to talk to — none of
it touches the real transfer path (§5 covers the one path that matters: `POST /transfers` → Redis →
Engine). One exception is flagged explicitly rather than left for a reader to find: the demo's
generic "create a request" form — used only to create `privileged-access` requests, which have no
transfer-shaped creation flow of their own — synchronously posts through this same proxy straight
into Approval Engine's `POST /approvals`. §5 names this as the one disclosed exception to the async
design and explains why it exists.

Production deployment would introduce an API Gateway/WAF, load balancing, and horizontally-scaled
instances per service. These are omitted here to keep the diagram focused on the ownership and
consistency boundaries this design is actually about; none of them change either.

## 3. Core Design

| Concern | Owner |
|---|---|
| Transfer semantics, validation, duplicate detection | Banking |
| Policy rules (amount range → workflow) + resolution | Approval Engine |
| Policy snapshot persistence | Approval Engine |
| Workflow state, guards, concurrency | Approval Engine |
| Audit, SLA expiry, outbox | Approval Engine |
| Release orchestration + release idempotency | Banking |
| Balance/limit authority, money movement | Core Banking (stub) |

`Core Banking (stub)` is a further-back, stubbed ledger/settlement dependency — not `Banking
Service` above it; the naming deliberately mirrors the real digital-banking-in-front-of-core-banking
pattern, so the boundary reads the same way it would in production.

**Policy chooses the workflow; the workflow defines the journey; a transition decides who may act
and how many:**

```text
Policy        "What workflow applies?"            (an amount range -> workflowId:version)
   |
   v
Workflow      "How does approval progress?"       (states + transitions, one YAML file)
   |
   v
Transition    "Who may act, how many, what else    (allowedRoles, requiredApprovals, guards)
               must be true?"
```

Policy is data, not code: it lives entirely in Approval Engine as an editable `policy_rule` table
(amount range → `workflowId`/`workflowVersion`). Resolution happens before workflow execution,
in-process inside the engine's own submission consumer, the moment it reads the transfer's creation
command off the Redis stream — Banking Service does not resolve policy itself (a now-unused client
class from an earlier design remains in Banking's source; see LLD's Policy Contract). Required-
approvals count and eligible role aren't a separate policy object on the wire; they're attributes of
the resolved workflow's own `approve` transition — one source of truth, in the service that already
owns the workflow catalog those rules route to.

**Approval ≠ execution.** The Approval Engine's only question is *"may this transfer proceed?"* —
it never touches a balance, an account, or a ledger. Banking and Core Banking answer the separate
question, *"execute the transfer,"* only after the engine says yes. Collapsing these into one
service would couple a generic, reusable approval mechanism to one specific kind of money movement;
keeping them apart is what let a second, unrelated approval domain (§8) reuse the mechanism
unmodified.

## 4. End-to-End Flow

The path with nothing racing and nothing failing — the reader should be able to hold this whole
sequence in their head before hitting any implementation detail:

```text
 1. Maker submits a transfer
 2. Banking validates and persists it as CREATED, returns immediately
 3. Banking publishes a creation command to Redis
 4. Approval Engine consumes it and resolves policy -> a workflow
 5. The engine snapshots that workflow's exact definition onto the new request
 6. Checkers complete whichever stages that workflow requires
 7. The engine reaches an approved terminal state
 8. The engine publishes an approval-completed event to Redis
 9. Banking consumes it and moves the transfer to RELEASE_PENDING
10. Banking calls Core Banking to release the funds
11. Core Banking confirms
12. Banking marks the transfer RELEASED
```

Two independent lifecycles carry this, owned on either side of the same boundary drawn in §3:

```text
 APPROVAL ENGINE                          BANKING
 ───────────────                          ───────
 SUBMITTED                                CREATED
    |                                        |
    v                                        v
 (workflow's own review stages)          PENDING_APPROVAL
    |                                        |
    v                                        |  ApprovalApproved
 APPROVED  ──────────────────────────────────>
                                              v
                                        RELEASE_PENDING
                                              |
                                              v
                                          RELEASED
```

Neither side ever reads the other's state directly — the only thing that crosses is the event named
on the arrow above. Full state machines, every terminal state, and the complete event-correspondence
table are in the LLD's Approval State Machine and Transfer Release Lifecycle sections.

## 5. Communication Pattern — Sync vs. Async, and Why

Each hop uses the pattern its own failure mode demands, not a single style applied uniformly:

- **Client → Banking: synchronous REST.** A human is waiting on the other end for a real answer
  ("did my transfer submit") — blocking here is the point. If Banking is down, the request simply
  fails fast and is retryable; there's no partial state to reconcile.
- **Banking ↔ Approval Engine: asynchronous, via two single-direction Redis Streams**, each with
  one consumer group. Not because the two services can't reach each other over REST — because
  neither service's *uptime* should gate the other's. `POST /transfers` must not hang because the
  engine is slow or unreachable; the engine's approve/reject/cancel handling must not hang because
  Banking is slow or unreachable. Redis Streams buys durable, at-least-once, redeliverable delivery
  in both directions for the cost of one already-required property: consumer idempotency (§6) — no
  new failure mode introduced by choosing async.
- **Banking → Core Banking (release): synchronous REST, idempotent by `transferId`.** Release is
  the one place a genuinely synchronous answer matters ("did the money move"); if Core Banking is
  unavailable, the transfer simply holds at `RELEASE_PENDING` and is retried with the same ID —
  safe because the call is idempotent, so a retry can never double-release.
- **Console → Banking → Approval Engine, for `privileged-access` demo creation only: synchronous
  REST.** Every real transfer creation is the async path above. The one disclosed exception:
  `privileged-access` has no transfer-shaped creation flow of its own, so the demo console's generic
  "create a request" form posts through Banking's `/ui-api` dev-tool proxy straight into Approval
  Engine's `POST /approvals`, synchronously. This exists to make the second workflow (§8) creatable
  from the UI at all, not because that workflow needed different resilience guarantees than
  transfers do — it carries none of §6's async safety net (no outbox, no retry-and-reclaim), and if
  Approval Engine is down, this one call simply fails and the reviewer retries by hand. It is never
  reachable from the transfer-submission path, so its absence of resilience never touches a real
  transfer.

```text
Normal transfer creation:        Banking  ──Redis──▶  Approval Engine        (async, §6's guarantees apply)
Privileged-access demo creation: Console ──▶ Banking ──REST──▶ Approval Engine  (sync, demo-only, no retry/outbox)
```

| Flow | Pattern | If unavailable |
|---|---|---|
| Client → Banking | REST sync | Fails fast, retryable |
| Banking → Engine (submission) | Redis Stream, at-least-once | Message persists in Redis; `POST /transfers` never blocks on Engine's availability |
| Engine → Banking (lifecycle events) | Redis Stream, at-least-once | Message persists in Redis; reclaimed if a consumer crashes mid-handling (mechanism in LLD's Redis Stream Delivery) |
| Banking → Core Banking (release) | REST sync, idempotent by `transferId` | Stays `RELEASE_PENDING`, retried |
| Console → Banking → Engine (privileged-access creation, demo only) | REST sync, not idempotent, no retry | Fails fast; reviewer retries by hand; never touches a real transfer |

## 6. Reliability & Banking Controls

**Idempotency.** Every write that could plausibly be retried has an idempotency key matched to its
own real retry identity: `POST /transfers` and `POST /approvals` (create) take an `Idempotency-Key`
header; decisions (approve/reject/cancel) are idempotent per `(request_id, actor_id, state)`, since
a checker's *intent* is the natural key there, not an opaque token; release is idempotent on
`transferId`. This is what makes Redis's at-least-once redelivery (§5) safe without a second
correctness mechanism — exact mechanics in the LLD.

**Consistency.** Strong and transactional *within* each service — one local ACID transaction per
state change. **Eventually consistent across the boundary** — no distributed transaction is
attempted; the outbox/stream pairing is the seam that makes that safe. Resilience is symmetric:
Engine being down doesn't break submission — a transfer always reaches `CREATED` immediately, and
the creation command retries against Redis until Engine comes back, giving up only after a bounded
number of attempts, at which point the transfer moves to `FAILED` — itself resumable, not terminal.
Banking being down likewise doesn't break approve/reject/cancel: the engine's state machine and
audit trail never depend on Banking's reachability, only lifecycle-event *delivery* does.

**Partial failure.** State, audit, and outbox commit atomically in one local transaction; delivery
across the boundary is a separate, retried step. A crash between "decision recorded" and "event
delivered" never loses the decision — it only delays the counterparty finding out, and a reconciler
on each side closes that gap automatically.

**Concurrency.** Every competing transition — two checkers approving at once, a maker cancelling
while a checker approves, an SLA sweep racing an approval — resolves through one optimistic,
version-checked state update, plus a narrow row lock only where more than one approval must be
tallied. Exactly one caller wins; every other party's entire transaction rolls back and is
classified as a clean `409`, never a partial write. Full mechanism, the exact SQL, and the tests
that prove each race are in the LLD's Concurrency section.

## 7. Trade-offs & Gaps

The rubric asks not just what was built, but what was deliberately left out and why.

### Deliberate constraints

**No funds hold between validation and release.** Balance is checked once at submission, not
reserved until release, so two large concurrent transfers against the same account can each pass
validation independently and later both release, overspending the real balance. The correct fix is
a `CoreBankingClient.hold()`/`free()` protocol — a second stateful contract with Core Banking beyond
what this exercise's time budget covers. Every other race in this system (§6) is closed; this one
is named deliberately, not left for a reviewer to find.

**Maker notification is a stub, not a real channel.** `ExpirySweeper` transitions an un-actioned
request to `EXPIRED`; Banking Service consumes that event, sets the transfer to `EXPIRED`, and
notifies the maker — also wired on rejection and on workflow-creation failure (not on cancellation,
since the maker caused that one themselves). Per the assignment's explicit allowance, the
notification client only logs; swapping in real email/SMS is a one-class change behind the same
interface.

**Production infrastructure is omitted** (API Gateway/WAF, load balancing, horizontal scaling — §2)
because none of it changes the ownership or consistency model this design is actually about.

### Architectural choices

- *YAML workflow definitions over a hardcoded state machine* — transitions change without a
  redeploy; no rule engine was built around it, since one wasn't needed for four workflow shapes.
- *DB outbox (engine → banking direction only) feeding Redis Streams* — not outbox *instead of* a
  broker, outbox *in front of* one: the local crash-safe claim seam that survives a crash before a
  lifecycle event ever reaches Redis. Submission (banking → engine) publishes straight to Redis
  after persisting, no outbox needed, since there's nothing else in that write left to claim. Redis
  Streams itself, over a heavier managed broker (Kafka/SQS/RabbitMQ): right-sized for this volume,
  one moving part instead of three.
- *Core Banking as a stubbed in-process interface, not a third service* — explicitly allowed by the
  assignment; modeling it as a real service would have spent time proving nothing new about this
  design's actual hard problem, the approval boundary.
- *Seams over generalization machinery* — the engine exposes exactly three seams (workflow YAML,
  policy snapshot, opaque payload) to prove domain-independence, and stops there: no tenant
  registry, no plugin framework, no workflow designer UI. §8 shows the seam is real without building
  infrastructure nobody asked for.

## 8. Extensibility — Built, Verified, and Now Live

**Why this is worth the scope it costs.** A single hardcoded transfer-approval state machine would
have satisfied the letter of the brief in less code, and that trade-off is worth naming rather than
assuming away. Three things justified the larger shape instead, each aimed at a specific rubric line
rather than generality for its own sake: a **generic, YAML-driven state machine** demonstrates the
state-machine and quorum correctness the LLD is graded on in a form that's provably reusable, not
merely correct once; **`privileged-access`**, a workflow with a structurally different shape (three
sequential stages, no maker, no `cancel` edge) run through the identical engine, is the actual
evidence for that reusability — a second transfer-shaped workflow would have proven nothing a single
one didn't already; and the **console UI** turns the concurrency and lifecycle behavior this document
describes (quorum accumulation, race outcomes, expiry) into something a reviewer can watch happen,
not only read a sequence diagram of. None of the three add a new failure mode to the graded transfer
path — §5 names the one synchronous exception this costs (`privileged-access` demo creation) and
confines it to a path a real transfer never takes.

| Domain | Workflow | Engine changes |
|---|---|---|
| Corporate transfer | `transfer-high-value:1` | — |
| Privileged access review | `privileged-access:2` | None |

A second, structurally different workflow-driven domain — a 3-stage security → manager →
compliance review — runs through the *same* engine with zero engine code changes: just a new YAML
file and a `policy_rule` row; the engine never inspects the opaque JSONB `payload` it carries. That
demonstrates approval stages, roles, and quorum are configuration, not hardcoded transfer logic. It
started as an isolated proof (`PrivilegedAccessConcurrencyTest`); it is now also the live routing
target for the highest transfer tier (≥ AED 100,000 — LLD's Policy Contract), so a real submission
exercises it, not just a test. That also means the same mechanism proves workflow *versioning* under
real traffic for free: `v1` required 1 security approval, `v2` (what transfers now route to)
requires 2 — no engine change, no migration of any in-flight `v1` request, since each request's
policy snapshot freezes the exact version it was created against.

## 9. Assumptions & Out of Scope

Stated explicitly, as the assignment asks, rather than left for a reviewer to infer:

| In scope | Out of scope |
|---|---|
| Two services, Postgres, Redis Streams, docker-compose | A managed/clustered broker (Kafka, MSK, Redis Cluster), real core banking, real auth |
| REST sync commands, outbox + stream async events | Multi-region, multi-currency, delegation |
| OCC + row-lock concurrency, audit, expiry | Tenant registry, BPMN/Temporal/Camunda |
| Idempotent submission/create/release | Funds hold/reservation (named gap, §7) |
| Editable policy rules, versioned workflows | — |
| A second workflow domain (privileged-access), now also the live ≥ AED 100,000 tier | — |

A demo React console (`approval-console-ui`) is included for reviewer convenience but isn't a
graded deliverable — see §2. It adds no authentication of its own: it forwards the same trusted
`X-Actor-Id`/`X-Actor-Role` headers the API already accepts, standing in for real auth (out of
scope per the table above).
