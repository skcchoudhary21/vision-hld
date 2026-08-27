# Low-Level Design — Transfer Approval System

## Approval State Machine (Engine)

`APPROVED` means the approval requirement is satisfied — not that money has moved.

```mermaid
stateDiagram-v2
    [*] --> SUBMITTED
    SUBMITTED --> APPROVED: auto_approve\n(transfer-auto-release only)
    SUBMITTED --> PENDING_APPROVAL: require_approval\n(single-checker / high-value)
    PENDING_APPROVAL --> APPROVED: approve [approvals_satisfied, actor_is_not_maker]
    PENDING_APPROVAL --> REJECTED: reject [allowedRoles: TRANSFER_CHECKER]
    PENDING_APPROVAL --> CANCELLED: cancel [actor_is_maker]
    PENDING_APPROVAL --> EXPIRED: expire [sla_expired]
```

Shown merged for readability; `transfer-auto-release` only has the top edge, the other two
transfer workflows only have the bottom five — no single workflow contains both (see Workflow
Definitions below).

## Transfer Release Lifecycle (Banking Service)

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> VALIDATED
    VALIDATED --> REJECTED: validation failure
    VALIDATED --> WAITING_FOR_APPROVAL
    WAITING_FOR_APPROVAL --> RELEASE_PENDING: ApprovalApproved
    WAITING_FOR_APPROVAL --> REJECTED: ApprovalRejected
    WAITING_FOR_APPROVAL --> CANCELLED: ApprovalCancelled
    WAITING_FOR_APPROVAL --> EXPIRED: ApprovalExpired
    RELEASE_PENDING --> RELEASED: core banking confirms
```

`WAITING_FOR_APPROVAL` mirrors the engine's `PENDING_APPROVAL` but is not shared state —
each event applies only if Transfer is still in the expected state (a lost race is a logged
no-op). No `RELEASE_FAILED`: a transient core-banking failure retries in place.

## Workflow Definitions (one fixed-shape YAML per tier, not guard-branching in one workflow)

Every workflow YAML under `approval-engine/src/main/resources/workflow/definitions/` declares
its own states, transitions, and per-transition `allowedRoles`/`requiredApprovals` — routing
between tiers happens *before* the engine, by picking which workflow to instantiate (see Policy
Contract below), not by a guard branching inside one shared workflow:

| Workflow (`workflowId:version`) | States | `approve` requires |
|---|---|---|
| `transfer-auto-release:1` | SUBMITTED → APPROVED | 0 approvals (unconditional transition) |
| `transfer-single-checker:1` | + PENDING_APPROVAL, REJECTED, CANCELLED, EXPIRED | 1 × `TRANSFER_CHECKER` |
| `transfer-high-value:1` | same shape as single-checker | 2 × `TRANSFER_CHECKER` |
| `privileged-access:2` | SUBMITTED → SECURITY_REVIEW → MANAGER_APPROVAL → COMPLIANCE_REVIEW → APPROVED | 2 × `SECURITY_CHECKER`, then 1 × `MANAGER_CHECKER`, then 1 × `COMPLIANCE_CHECKER` |

All definitions load once at startup into a `WorkflowRegistry` keyed by `(workflowId, version)`;
guards (`approvals_satisfied`, `actor_is_maker`, `actor_is_not_maker`, `sla_expired`) are a small
fixed Java registry looked up by name — no expression language, no runtime reconfiguration. Role
eligibility is declarative (`allowedRoles` on the transition), not a guard function.

## Policy Contract

`policy_rule(id PK, min_amount_minor_units, max_amount_minor_units nullable, workflow_id,
workflow_version)` — an editable table in Approval Engine, not a formula in Banking. `GET
/policy-rules/resolve?amountMinorUnits=N` returns the first row covering `N` as `{workflowId,
workflowVersion}` (404 `POLICY_RULE_NOT_FOUND` if none covers it). Seeded once from
`application.yml`'s ceiling values (AED 5,000 / 50,000 minor units) into the three transfer
tiers; editable afterward via `PUT /policy-rules`, no redeploy.

Banking's `PolicyResolver` only resolves *which workflow* — required-approvals count and
eligible role aren't a separate policy object on the wire; they're the resolved workflow's own
`approve` transition (`requiredApprovals`, `allowedRoles`), frozen into `policy_snapshot`
(embeds the full `WorkflowDefinition`) at creation and never re-resolved.

## API Contracts

**`GET /policy-rules/resolve?amountMinorUnits=N`** (Engine) → `{ "workflowId": "transfer-single-checker", "workflowVersion": 1 }`, or `404 POLICY_RULE_NOT_FOUND`.

**`POST /approvals`** (Engine; header `Idempotency-Key`) — caller names the already-resolved workflow, not a policy shape.
```json
// Request
{
  "requestId": "abc123", "requestType": "TRANSFER_APPROVAL", "makerId": "maker-1",
  "workflowId": "transfer-single-checker", "workflowVersion": 1, "policyVersion": "v1",
  "payloadJson": "{\"transferId\":\"abc123\",\"amount\":500000}",
  "expiresAt": "2026-08-26T10:00:00Z"
}
// 200 Response
{ "requestId": "abc123", "state": "PENDING_APPROVAL", "version": 1 }
```

**`POST /approvals/{id}/approve`** (no `Idempotency-Key` — idempotent per `(request_id,
actor_id, state)`)
```json
// Request                         // 200 Response
{ "actorId": "checker-1",          { "requestId": "transfer-abc123",
  "actorRole": "TRANSFER_CHECKER" }  "state": "APPROVED", "version": 2 }
```
```json
// 409 CONCURRENT_STATE_CHANGE           // 409 INVALID_STATE_TRANSITION
{ "code": "CONCURRENT_STATE_CHANGE",     { "code": "INVALID_STATE_TRANSITION",
  "requestId": "transfer-abc123",          "requestId": "transfer-abc123",
  "currentState": "APPROVED",              "currentState": "APPROVED",
  "requestedAction": null }                "requestedAction": "approve" }
```
`reject`/`cancel` share this shape (`ActorCommandDto`/`ApprovalResponseDto`/`ErrorResponseDto`);
`GET /approvals/{id}` returns `ApprovalResponseDto`.

| Error `code` | HTTP | When |
|---|---|---|
| `CONCURRENT_STATE_CHANGE` | 409 | Guarded UPDATE lost the race; action was legal, someone else won it first |
| `INVALID_STATE_TRANSITION` | 409 | Action was never legal from any state that could reach the current one |
| `IDEMPOTENCY_CONFLICT` | 409 | Same `Idempotency-Key`/`requestId` replayed with a different body |
| `FORBIDDEN_ACTION` | 403 | Actor role not eligible for this transition (or maker self-approving) |
| `NOT_FOUND` / `WORKFLOW_NOT_FOUND` / `POLICY_RULE_NOT_FOUND` | 404 | Unknown request / workflow / no policy rule covers the amount |
| `INVALID_REQUEST` | 400 | e.g. `mine=true` without `X-Actor-Role` |

**`POST /transfers`** (Banking; header `Idempotency-Key`) — creates and submits in one call.
```json
// Request
{ "makerId": "maker-1", "fromAccount": "ACC-1", "toAccount": "ACC-2",
  "amountMinorUnits": 500000, "currency": "USD" }
// 200 Response
{ "transferId": "abc123", "state": "WAITING_FOR_APPROVAL" }
```
`GET /transfers/{id}` returns `TransferResponseDto`. Internal webhook `POST /internal/events`
(`X-Event-Id`, `X-Event-Type` headers; body `{"requestId": "..."}`) is the relay's delivery
endpoint, not client-facing.

## Data Model

```
-- approval DB
approval_request(request_id PK, request_type, state, version, maker_id,
                  workflow_id, workflow_version, policy_snapshot jsonb, payload jsonb,
                  created_at, expires_at)
approval_decision(decision_id PK, request_id, actor_id, actor_role, state, decision,
                   created_at, UNIQUE(request_id, actor_id, state))
audit_log(audit_id PK, request_id, actor_id, actor_role, action,
          previous_state, new_state, created_at, metadata)
idempotency_key(idem_key PK, command_type, request_id, request_hash, result jsonb, created_at)
outbox(event_id PK, request_id, event_type, event_version, payload jsonb,
       created_at, published_at, claimed_at)
policy_rule(id PK, min_amount_minor_units, max_amount_minor_units nullable,
            workflow_id, workflow_version)

-- transfer DB
transfer(transfer_id PK, maker_id, from_account, to_account, amount_minor_units,
         currency, state, approval_request_id, idempotency_key UNIQUE,
         expires_at, created_at)
processed_event(event_id PK, processed_at)
```

## Sequence Diagrams

**Auto-release (0 approvals required)**

```mermaid
sequenceDiagram
    participant T as Banking Service
    participant E as Approval Engine
    participant R as Outbox Relay
    T->>E: GET /policy-rules/resolve?amountMinorUnits=... -> transfer-auto-release:1
    T->>E: POST /approvals (Idempotency-Key, workflowId=transfer-auto-release)
    E->>E: only transition from SUBMITTED is unconditional -> APPROVED
    E->>E: commit: state + audit + outbox(ApprovalSubmitted, ApprovalApproved)
    E-->>T: 200 { state: APPROVED }
    R->>E: poll: claim unpublished (FOR UPDATE SKIP LOCKED)
    R->>T: POST /internal/events (ApprovalApproved)
    T->>T: dedupe by event_id, release() -> RELEASE_PENDING -> RELEASED
```

**Multi-approver — the race diagram** (Checker A, Checker B approve concurrently, `required=1`;
ground truth: `ApprovalConcurrencyTest.twoCheckersApprovingSimultaneously_exactlyOneWins`)

```mermaid
sequenceDiagram
    participant A as Checker A
    participant B as Checker B
    participant E as Approval Engine
    A->>E: POST /approve
    B->>E: POST /approve
    E->>E: A: SELECT ... FOR UPDATE (lock acquired)
    E->>E: B: SELECT ... FOR UPDATE (blocks on A)
    E->>E: A: guard passes, UPDATE WHERE state=PENDING_APPROVAL AND version=1 -> rows=1
    E->>E: A: commit (state=APPROVED, v2), lock released
    E-->>A: 200 { state: APPROVED, version: 2 }
    E->>E: B: lock granted, reads state=APPROVED (not PENDING_APPROVAL)
    E-->>B: 409 CONCURRENT_STATE_CHANGE { currentState: APPROVED }
```

The row lock serializes quorum *counting* (needed for N>1); the guarded UPDATE's version check
is what actually decides the winner and is what the sweeper below races against with no lock.

**Expiry vs. approve** (optimistic race, no row lock on the sweeper's path; ground truth:
`ExpiryVersusApproveConcurrencyTest.approveVersusExpire_exactlyOneWins`)

```mermaid
sequenceDiagram
    participant S as Expiry Sweeper
    participant C as Checker
    participant E as Approval Engine
    S->>E: expireOne(id, expectedVersion=1)
    C->>E: POST /approve
    par concurrently
        E->>E: UPDATE WHERE state=PENDING_APPROVAL AND version=1 -> EXPIRED
    and
        E->>E: UPDATE WHERE state=PENDING_APPROVAL AND version=1 -> APPROVED
    end
    Note over E: exactly one UPDATE affects rows=1; the other rows=0 -> no-op / 409
```

## Concurrency / Race Handling

Every competing transition resolves through one guarded conditional UPDATE:

```sql
UPDATE approval_request
   SET state = :new_state, version = version + 1
 WHERE request_id = :id AND state = :expected_state AND version = :expected_version;
-- rows = 1 -> won ; rows = 0 -> lost race or illegal transition
```

On `rows = 0` the whole transaction rolls back (decision insert, audit, outbox — all of it);
re-reading current state then classifies the failure: `409 CONCURRENT_STATE_CHANGE` if it was a
legal predecessor, `409 INVALID_STATE_TRANSITION` if it could never have led here. Quorum
counting additionally takes a `SELECT ... FOR UPDATE` row lock (`ApprovalCommandService.
loadOrThrow`) — a deliberate exception to "no explicit locks," since counting committed
decisions is an aggregate read the guarded UPDATE alone can't protect: without it, two
checkers can each count only their own still-uncommitted vote, both see quorum unmet, and a
request that actually has enough approvals is stranded in `PENDING_APPROVAL` forever. Evidence:
`ApprovalConcurrencyTest`, `ExpiryVersusApproveConcurrencyTest`.

**Why a lock, not a lock-free alternative:** considered and rejected for this contention shape
(at most `requiredApprovals` actors, one row, a millisecond-scale critical section):
*atomic counter column* (`UPDATE ... SET approvals_count = approvals_count + 1 RETURNING`) drops
the explicit lock but denormalizes the count against `approval_decision` as a second source of
truth that can drift; *`SERIALIZABLE` isolation + retry* trades a short wait for a full
abort-and-redo plus retry code, for contention this shallow; *splitting "record vote" from
"evaluate quorum" into two transactions* (the outbox philosophy used elsewhere here) closes the
race without a lock but turns a synchronous decision eventually-consistent for no real gain at
this scale. A `PESSIMISTIC_WRITE` held for one short cycle, on one row, contended by a handful
of actors, is the simplest option that's actually correct.

## Redis Stream Delivery

Two streams, one consumer group each: `stream:transfer-approval-create` (`approval-engine-workers`)
and `stream:approval-lifecycle-events` (`banking-service-workers`). Both are at-least-once —
a message stays in the group's pending-entries list until `XACK`'d; `SubmissionCommandReconciler`
/ `LifecycleEventReconciler` reclaim anything idle past 30s via `XPENDING` + `XCLAIM` (the Redis-native
equivalent of `OutboxClaimService`'s `claimed_at` staleness window) and retry it. The submission
side additionally gives up after 3 delivery attempts, publishing `ApprovalCreationFailed` onto
the lifecycle stream so banking-service can move the transfer to `FAILED` and notify the maker —
the lifecycle side has no equivalent failure state to move to, so it logs loudly past 5 attempts
rather than silently dropping the message (a full dead-letter mechanism is out of scope).

Redelivery is safe everywhere it can happen because every consumer here was already idempotent
before Redis existed: `ApprovalCommandService.create()` by `(Idempotency-Key, body hash)`,
`ApprovalEventListener.handle()` by `processed_event.event_id`.

## Failure Semantics

| Failure | Behavior |
|---|---|
| Engine unreachable during `submit()` | Transfer still reaches `CREATED` immediately; the creation command persists in `stream:transfer-approval-create` and `SubmissionCommandReconciler` retries it until Engine comes back, giving up only after `MAX_DELIVERY_ATTEMPTS` (3) — at which point the transfer moves to `FAILED` and the maker is notified |
| Banking Service unreachable during approve/reject/cancel | Engine still transitions/audits; only delivery delays |
| Relay crashes mid-publish | Row stays `claimed_at`-set; reclaimed after 30s |
| Duplicate event delivery | `processed_event(event_id)` dedupe — no-op replay |
| Core banking release fails | Stays `RELEASE_PENDING`, retried with same `transferId` |
| Lost race (approve/reject/cancel/expire) | Transaction rolls back; `409` or logged sweeper no-op |
