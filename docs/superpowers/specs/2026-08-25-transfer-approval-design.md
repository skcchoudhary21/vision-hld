# Transfer Approval System — Design Spec (Locked)

Status: approved for implementation. This is the internal working spec; the
submitted HLD/LLD (`docs/hld.md`, `docs/lld.md`, ≤4 pages combined) is a
compressed derivative of this document, not a replacement for it.

Context: Vision Bank take-home assignment (`EM_Assignment.pdf`). Budget: 30
hours over 1 week. Deliverables: HLD, LLD, working code (docker-compose,
one-command start), README, tests.

## 1. Thesis

Two services, one real tenant. **Transfer Domain owns what a transfer
means; Approval Engine owns how an approval progresses.**

The engine is written with a domain-independent boundary (three seams —
workflow definition, policy snapshot, opaque request envelope) to
demonstrate the boundary is real, but no generalization *machinery* is
built around it: no tenant registry, no dynamic config, no plugin
framework, no second tenant, no rule engine, no workflow designer.

Filter for every future addition: *does this help satisfy the assignment,
prove correctness, or explain an architectural trade-off? If no, it does
not enter the doc or the implementation.*

## 2. Stack

Java 21, Spring Boot 4.1.x, Gradle 8.x, Postgres (one DB per service),
DB-backed transactional outbox (no broker), docker-compose.

## 3. Repo layout

Monorepo for submission convenience; each service fully independent
(own build file, own Dockerfile, own tests — no shared parent module, no
shared DTO jar). Small, explicitly-noted duplication of the event JSON
shape across services is preferred over coupling their builds.

```
vision-hld/
├── docker-compose.yml
├── README.md
├── docs/{hld.md, lld.md}
├── transfer-service/
└── approval-engine/
```

## 4. Service boundary & data ownership

| Concern | Owner |
|---|---|
| Transfer semantics, validation orchestration, duplicate detection | Transfer |
| Policy resolution (threshold → approvals required) | Transfer |
| Policy snapshot persistence | Approval Engine |
| Workflow state, guards, concurrency | Approval Engine |
| Audit, SLA expiry, outbox | Approval Engine |
| Release orchestration + release idempotency | Transfer |
| Balance/limit authority, money movement | Core Banking (stub) |

Neither service writes the other's database. Core Banking is **not** a
third deployable service — it is an interface (`CoreBankingClient`) inside
Transfer Domain with a fake/stub implementation, since the assignment
explicitly allows core banking to be mocked. If containerized at all for
realism, it is a single trivial stub endpoint, not a modeled service.

## 5. Communication & failure handling

| Flow | Pattern | If unavailable |
|---|---|---|
| Client → Transfer | REST sync | Fails fast, retryable |
| Transfer → Engine (create/command) | REST sync + idempotency key | Retry same key, no duplicate workflow |
| Engine → Transfer (lifecycle events) | Outbox + async delivery | Event durable in DB, relay retries on recovery |
| Transfer → Core Banking (release) | REST sync, idempotent | Transfer holds `RELEASE_PENDING`, retried with same ID |

Consistency model: strong/transactional within each service; **eventually
consistent across the boundary** — no distributed transaction. The outbox
is the seam that makes partial failure safe.

**Resilience is deliberately asymmetric between the two directions**, worth
stating explicitly rather than leaving implicit in the table above: Engine
being unavailable breaks `submit()` synchronously (it's a blocking REST
call on the critical path); Transfer being unavailable does **not** break
approve/reject/cancel (the engine's own state machine and audit trail don't
depend on Transfer being reachable — only outbox delivery does, and that's
async and retried). This asymmetry is intentional, not an oversight: the
engine's correctness never depends on Transfer's availability.

**Known gap, not built — no funds hold between validation and release.**
Balance is checked once at submission; nothing reserves funds until
release actually happens. Two large concurrent transfers against the same
account can each pass validation independently and both later release,
overspending the real balance — a real double-spend risk in a system this
otherwise careful about races. The correct fix: `CoreBankingClient` would
need a `hold(fromAccount, amount, transferId)` call made at submission
(before persisting), consumed on release and explicitly freed on
`ApprovalRejected`/`ApprovalCancelled`/`ApprovalExpired`. Not implemented
here — it's a second stateful protocol with core banking beyond what the
30-hour budget covers, but it's the one gap in this design that a senior
banking reviewer should not have to find themselves.

## 6. State machines (two, deliberately separate)

**Approval Engine (generic):**
`SUBMITTED → APPROVED` (no approval required) or `SUBMITTED →
PENDING_APPROVAL → {APPROVED | REJECTED | CANCELLED | EXPIRED}`.
`APPROVED` means the approval requirement is satisfied — 0 or N
approvals converge on the same state and the same `ApprovalApproved`
event. No `AUTO_APPROVED`, no `COMPLETED`.

**Transfer Domain (release lifecycle):**
`CREATED → VALIDATED → WAITING_FOR_APPROVAL → RELEASE_PENDING → RELEASED`,
with `VALIDATED → REJECTED` on validation failure. `WAITING_FOR_APPROVAL`
mirrors the engine's `PENDING_APPROVAL` logically but is not shared
state — engine stays authoritative for the approval outcome, Transfer
stays authoritative for release. No `RELEASE_FAILED` state: a transient
core-banking failure retries in place in `RELEASE_PENDING`; a terminal
core-banking rejection (frozen account, compliance block) is explicitly
out of scope.

Cross-service race safety: every event the engine emits carries the
transfer version it acted on; Transfer applies it only if still in the
expected state/version — a lost race (maker already cancelled) is a
logged no-op, not an error.

## 7. Declarative workflow definition (seam #1) — scoped small

Transitions loaded from one YAML file at startup into a `Map` keyed by
`(fromState, event)`; guards are a small fixed Java registry referenced
by name (`approval_required`, `approvals_satisfied`, `actor_is_maker`,
`actor_is_eligible_checker`, `sla_expired`). No expression language, no
dynamic/runtime reconfiguration, no second workflow definition.

**Tripwire:** if the loader (parse + validate + guard registry + one
end-to-end test) is not working within 2 hours, fall back to a hardcoded
enum + map and note the trade-off in the README. Do not let this item
expand past its box.

Domain rules (`amount > threshold`, `balance >= amount`) are forbidden
inside engine guards — a domain rule in a guard collapses the
generic-engine boundary.

Startup validation (fail fast, not mid-request): every transition's
`from`/`to` are declared states; `initialState` is a declared state;
transition names are unique; every transition's `guard` name resolves
in the `GuardRegistry` — the registry already throws on an unknown name
(§12's guard lookup), so this validation is just calling that lookup
once for every transition at wiring time instead of only on first use.

## 8. Policy resolution & snapshot (seam #2)

Resolved inside Transfer Domain at submission time
(`PolicyResolver.resolve(context) -> ApprovalPolicy{requiredApprovals,
eligibleRoles, makerCanApprove}`), frozen into an immutable
`policy_snapshot` (JSONB) the engine persists and never re-resolves. A
later policy/config change never re-judges an in-flight request.

## 9. Request envelope (seam #3)

`approval_request` carries `request_id, request_type, policy_version,
policy_snapshot, state, version, payload(JSONB), created_at,
expires_at`. The engine routes/audits/expires/executes without reading
`payload`.

## 10. Maker-cannot-approve-own-request

Enforced as a generic pre-transition **command guard** (actor == maker
AND `!policySnapshot.makerCanApprove` → reject), evaluated before the
`approve` transition — not encoded as a state-transition row, since it's
a command-level constraint, not a workflow shape.

## 11. Idempotency (split ownership)

| Operation | Owner | Mechanism |
|---|---|---|
| Transfer submission (client `Idempotency-Key`) | Transfer | stored key → replayed result, conflict on body mismatch |
| Workflow create (client `Idempotency-Key`) | Engine | stored key → replayed result, conflict on body mismatch |
| approve/reject/cancel decisions | Engine | `UNIQUE(request_id, actor_id)` — a decision is naturally idempotent per actor; retrying with the same actor replays existing state, except when that actor's own decision just completed quorum — the terminal-state check runs first, so that retry gets `409 CONCURRENT_STATE_CHANGE` instead (a follow-up `GET /approvals/{id}` shows the outcome) |
| Core-banking release | Transfer | `CoreBankingClient.release` is idempotent keyed on `transferId` — a redelivered `ApprovalApproved` event never moves money twice |

Replay of `create` with same key + different body → `409
IDEMPOTENCY_CONFLICT`; matching replay returns the stored result. The
engine does not and cannot guarantee business-operation (money
movement) idempotency — that belongs to Transfer, keyed on request ID.

**Why `approve`/`reject`/`cancel` don't take a client idempotency key:**
a workflow *decision* is a fact about one actor's action on one request
— the `(request_id, actor_id)` uniqueness constraint already makes
retrying the same actor's decision a no-op (it re-reads and returns
current state instead of double-inserting). A generic idempotency-key
store for these three commands would duplicate that guarantee with a
second mechanism. `create` is different: it's the one command that
*originates* a request, so a lost-response retry must not spawn a
second workflow — that's what the client-supplied key is for.

## 12. Concurrency — one mechanism for every race

Optimistic concurrency control via a single guarded conditional update,
used for every competing transition (checker-vs-checker,
cancel-vs-approve, expiry-vs-approve, reject-vs-cancel):

```sql
UPDATE approval_request
   SET state = :new_state, version = version + 1
 WHERE request_id = :id AND state = :expected_state AND version = :expected_version;
-- rows = 1 → won ; rows = 0 → lost race or illegal transition
```

On `rows = 0`, the entire command transaction rolls back (decision
insert, audit, outbox — all of it). Re-reading current state after
rollback distinguishes:
- `409 CONCURRENT_STATE_CHANGE` — current state was a legal predecessor;
  caller lost a legitimate race.
- `409 INVALID_STATE_TRANSITION` — current state could never have led
  here regardless of timing.

## 13. N-of-M approval transaction

An intermediate approval (count < required) commits the decision +
`APPROVAL_RECORDED` audit **without** a state transition, returning
`PENDING_APPROVAL`. Only the approval that satisfies the quorum attempts
the guarded transition to `APPROVED`; if that guarded update loses the
race (`rows = 0`), the whole transaction — including the decision insert
— rolls back, so a checker's approval is never persisted against a
request another actor already moved.

## 14. Audit

Append-only: `audit_id, request_id, actor_id, actor_role, action,
previous_state, new_state, created_at, metadata`. Current state lives on
`approval_request`; audit is history only, never replayed to reconstruct
state.

## 15. Expiry

Sweeper selects expired `PENDING_APPROVAL` rows in bulk but transitions
each individually through the same guarded update as every other
transition — never a bulk `UPDATE ... WHERE deadline < now`, so an
in-flight approval and expiry can't both "win." Engine emits
`ApprovalExpired`; Transfer owns maker notification (engine has no
users). This is the single place a shortcut would quietly break the
concurrency model — keep the sweeper on the per-row guarded path.

## 16. Events

Engine emits (via outbox): `ApprovalSubmitted, ApprovalApproved,
ApprovalRejected, ApprovalCancelled, ApprovalExpired`. Transfer owns
`TransferReleased, TransferReleaseFailed` after core banking confirms.
Engine never emits `TransferReleased` — it doesn't move money. Both the
auto-approve and N-approver paths converge on the same
`ApprovalApproved` event, so Transfer has exactly one release trigger.

## 17. Outbox & delivery semantics

State change + audit + outbox row committed in one transaction; a
polling relay publishes; at-least-once delivery. Every event carries
`event_id`; Transfer keeps `processed_event(event_id UNIQUE)` to dedupe.
No broker — an in-DB outbox is right-sized for corporate transfer
volume; if throughput or org boundaries later demand one, the outbox
remains the durable publish boundary underneath it.

The relay claims a batch via `SELECT ... FOR UPDATE SKIP LOCKED` and
marks those rows `claimed_at` in one short transaction (no HTTP call
inside it), then publishes and marks `published_at` outside that
transaction. This is what makes it safe to run more than one relay
instance without both delivering the same event on every pass — not
required for this exercise (one instance each), but a one-line query
change rather than a new subsystem, so it's cheap enough to do
correctly now instead of asserting it without building it. A claim
older than 30s with `published_at` still null is treated as stale and
re-claimable (crash recovery); redelivery from either case is still
just at-least-once, which `processed_event` already handles.

## 18. Data model

```
-- Approval DB
approval_request(request_id PK, request_type, state, version,
                  policy_version, policy_snapshot JSONB, payload JSONB,
                  created_at, expires_at)
approval_decision(decision_id PK, request_id FK, actor_id, actor_role,
                   decision, created_at, UNIQUE(request_id, actor_id))
audit_log(audit_id PK, request_id, actor_id, actor_role, action,
          previous_state, new_state, created_at, metadata)
idempotency_key(key PK, command_type, request_id, result, created_at)
outbox(event_id PK, request_id, event_type, event_version, payload,
       created_at, published_at)

-- Transfer DB
transfer(...), release_state(...), processed_event(event_id UNIQUE, processed_at)
```

## 19. API contracts (submission LLD needs full schemas — draft here)

```
POST /transfers                      → 201 { transferId, state }
POST /transfers/{id}/submit          → 202 { transferId, state }
POST /approvals/{id}/approve         (no Idempotency-Key — see §11)
  → 200 { requestId, state, decisionRecorded: true }
  → 409 CONCURRENT_STATE_CHANGE { code, requestId, currentState }
  → 409 INVALID_STATE_TRANSITION { code, requestId, currentState, requestedAction }
POST /approvals/{id}/reject          (no Idempotency-Key — see §11)
POST /approvals/{id}/cancel          (no Idempotency-Key — see §11)
GET  /approvals/{id}
```

## 20. Testing strategy

Priority order (matches rubric weight): state transitions (every legal
+ illegal move) → guards (maker-can't-approve-own, ineligible role,
insufficient approvals, expired) → N-approval accumulation →
concurrency (two checkers, cancel-vs-approve, expiry-vs-approve — exactly
one wins, loser's decision/audit/outbox all rolled back) → idempotency
(submit/approve/reject/cancel/release/event-delivery replay) →
convergence (auto and N-approval both hit the single release path).
Controller tests are lowest priority.

## 21. Out of scope

Real broker, real core banking, real auth, UI, multi-region,
multi-currency, delegation, dynamic workflow/policy config, tenant
registry, second tenant, BPMN/Temporal/Camunda, **a funds hold/reservation
between validation and release (§5) — the one named banking-correctness
gap in this design, not a silent omission.**

## 22. Build order

1. State machine (enum/map or tripwired YAML loader)
2. `PolicyResolver` / `ApprovalPolicy` / `PolicySnapshot`
3. DB schema with uniqueness/version constraints
4. API contracts (full request/response schemas, both 409 codes)
5. Three sequence diagrams for the submission doc — auto-release,
   multi-approver (drawn as two *concurrent* checkers racing the guarded
   update, not sequential — this is also the race-handling diagram),
   expiry
6. Concurrency/idempotency tests, then implementation to make them pass
7. docker-compose + README

No new architectural component after this point unless one of these
artifacts exposes an actual contradiction.
