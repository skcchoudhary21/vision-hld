# Multi-Stage, Multi-Workflow Approval Engine — Design

> Extends the transfer approval system (see
> `2026-08-25-transfer-approval-design.md`) to generalize the Approval
> Engine from one hardcoded two-state workflow into a genuinely
> workflow-agnostic engine: multiple named workflow definitions, N-stage
> sequential graphs (not just a single approval gate), per-stage N-of-M
> quorum, and a generic UI that renders whichever workflow a request used
> without knowing its shape in advance.

## 1. Why

The current engine works for exactly one shape: `SUBMITTED →
PENDING_APPROVAL → {APPROVED|REJECTED|CANCELLED|EXPIRED}`, with `approve`/
`reject`/`cancel` hardcoded to that shape in Java (`ApprovalCommandService`
compares `request.getState() != PENDING_APPROVAL` and calls
`guardedTransition(..., PENDING_APPROVAL, ..., APPROVED)` as literals, not
as a lookup against the loaded `WorkflowDefinition`). This was correct for
the original scope (spec §7: "no rule engine... no runtime
reconfiguration") but the HLD explicitly names supporting a second,
differently-shaped workflow as the proof this system's seams generalize
("Extensibility (Not Built)... not exercised by a second tenant" —
README's "what I'd do differently"). This spec is that seam, built for
real.

**Two example target workflows**, both must be expressible as pure YAML,
zero Java changes:

```
privileged-access (short, 3 human stages):
  SUBMITTED → SECURITY_REVIEW → MANAGER_APPROVAL → COMPLIANCE_REVIEW → APPROVED
                                                                       ↘ REJECTED / EXPIRED

transfer-approval (existing, unchanged in shape):
  SUBMITTED → PENDING_APPROVAL → APPROVED / REJECTED / CANCELLED / EXPIRED
```

A 6-stage human-review chain (RISK_REVIEW → SECURITY_REVIEW →
COMPLIANCE_REVIEW → LEGAL_REVIEW → MANAGER_APPROVAL → FINAL_APPROVAL →
APPROVED) is the same shape as `privileged-access`, just longer — no
additional engine capability needed once 3-stage works.

## 2. Explicitly out of scope

- **AND/OR composition of approval conditions.** Each stage keeps today's
  single N-of-M-from-one-role-pool quorum shape. A stage requiring "one
  RISK approver AND one AUDIT approver" is a real, separate feature
  (`ALL_OF`/`ANY_OF` composition over role-groups) — not built here.
- **Concurrent multi-version execution.** One active version per named
  workflow at a time. `workflow_version` is persisted per-request for
  audit/display; the engine does not keep an old version's definition
  loadable once a new one replaces it. An in-flight request created under
  a version that gets replaced mid-flight is a known, accepted gap (same
  risk class as redeploying any stateful service mid-transaction — not
  new to this change).
- **Per-stage SLA durations.** `expires_at` stays exactly as built: one
  request-level timestamp, set by the caller at creation, unchanged for
  the request's whole lifetime regardless of how many stages it passes
  through. A workflow-declared per-stage SLA (reset the clock on entering
  each new stage) is a real, separate feature — not built here.
- **Execution/business-lifecycle states inside the Engine's workflow.**
  `PROCESSING`, `COMPLETED`, `FAILED` (or equivalents) never appear in an
  Approval Engine workflow YAML. The Engine's workflow always terminates
  at one of its own `terminalStates`; execution is a downstream service's
  own concern, reported nowhere back to the Engine. (Resolved earlier in
  this design's brainstorm — see conversation: putting execution inside
  the Engine's state machine breaks the documented asymmetric-resilience
  property, "Transfer down does not break approve/reject/cancel.")

## 3. Workflow definition schema (extended)

```yaml
name: privileged-access
version: 1
initialState: SUBMITTED
terminalStates: [APPROVED, REJECTED, EXPIRED]

states:
  - id: SUBMITTED
    label: Submitted
  - id: SECURITY_REVIEW
    label: Security Review
  - id: MANAGER_APPROVAL
    label: Manager Approval
  - id: COMPLIANCE_REVIEW
    label: Compliance Review
  - id: APPROVED
    label: Approved
  - id: REJECTED
    label: Rejected
  - id: EXPIRED
    label: Expired

transitions:
  - name: submit
    from: SUBMITTED
    to: SECURITY_REVIEW
    guard: approval_required
  - name: approve
    from: SECURITY_REVIEW
    to: MANAGER_APPROVAL
    guard: approvals_satisfied
  - name: approve
    from: MANAGER_APPROVAL
    to: COMPLIANCE_REVIEW
    guard: approvals_satisfied
  - name: approve
    from: COMPLIANCE_REVIEW
    to: APPROVED
    guard: approvals_satisfied
  - name: reject
    from: SECURITY_REVIEW
    to: REJECTED
    guard: actor_is_eligible_checker
  - name: reject
    from: MANAGER_APPROVAL
    to: REJECTED
    guard: actor_is_eligible_checker
  - name: reject
    from: COMPLIANCE_REVIEW
    to: REJECTED
    guard: actor_is_eligible_checker
  - name: expire
    from: SECURITY_REVIEW
    to: EXPIRED
    guard: sla_expired
  - name: expire
    from: MANAGER_APPROVAL
    to: EXPIRED
    guard: sla_expired
  - name: expire
    from: COMPLIANCE_REVIEW
    to: EXPIRED
    guard: sla_expired

events:
  APPROVED: [ApprovalApproved]
  REJECTED: [ApprovalRejected]
  EXPIRED: [ApprovalExpired]
```

**Design choices, each deliberate:**

- **Action names (`approve`/`reject`/`expire`) repeat across every stage
  they apply to**, distinguished by `from`. This keeps the public API
  fixed — `POST /approvals/{id}/approve` means the same thing for every
  workflow shape, the engine resolves *which* transition that maps to
  from the request's current state. No new endpoints per workflow.
- **No `cancel` transitions shown above** — `privileged-access` in this
  example doesn't offer maker cancellation; `transfer-approval` does, at
  every `PENDING_APPROVAL`-equivalent stage that wants it. This is a
  workflow-authoring choice, not an engine capability gap — the guard
  registry already has `actor_is_maker` for it.
- **`events:` maps *state* → outbox event type(s), fired only when a
  transition lands on a state present in that map.** In practice this
  means terminal states only (nothing consumes intermediate-stage
  events today) but the mechanism doesn't hardcode "terminal" — a future
  workflow could legitimately want an event fired on entering a specific
  mid-workflow state too (e.g., notify a queue when `COMPLIANCE_REVIEW`
  is reached), so the map stays keyed by state, not restricted to
  `terminalStates`.
- **No `requiredApprovals`/`eligibleRoles` anywhere in this file.** Those
  stay exactly where they are today: caller-supplied, per-request, in
  `policy_snapshot` — now shaped per-stage (§5). The workflow YAML only
  ever defines *shape* (states, transitions, guard names), never
  *quantities*. This preserves the existing "engine stays domain-blind"
  principle for every workflow, not just `transfer-approval`.

## 4. Multiple workflows + selection

`WorkflowConfig`'s single `@Value("${workflow.definition-path}")` becomes
a directory scan: every `*.yaml` under
`approval-engine/src/main/resources/workflow/definitions/` is loaded via
`YamlWorkflowLoader`, validated (existing checks: `initialState` declared,
no duplicate transition *identity* — now `(name, from)` must be unique,
not just `name`, since the same name legitimately repeats across stages —
plus a new check: every transition's `guard` must resolve in
`GuardRegistry`, same as today, and every state with **zero** outgoing
transitions must appear in `terminalStates`, and vice versa), and
collected into a `Map<String workflowId, WorkflowDefinition>` bean
(`WorkflowRegistry`).

A second, separate file,
`approval-engine/src/main/resources/workflow/workflow-selection.yaml`,
maps request type to workflow id — the same shape as `PolicyResolver`'s
job, but selecting a *workflow*, not a threshold:

```yaml
selectors:
  - requestType: TRANSFER_APPROVAL
    workflowId: transfer-approval
  - requestType: PRIVILEGED_ACCESS
    workflowId: privileged-access
```

New component `WorkflowSelector.resolve(String requestType) →
WorkflowDefinition`, throwing a clear `IllegalStateException` at
*startup* if `workflow-selection.yaml` references a `workflowId` that
`WorkflowRegistry` never loaded (fail fast, same philosophy as
`WorkflowConfig`'s existing guard-name-at-startup validation) — and at
*request-creation time* if a `requestType` has no selector entry (a
caller error, not a race).

## 5. Data model changes

**`ApprovalRequest`** gains two columns, both frozen at creation, never
re-resolved (same pattern as `policy_snapshot` and `expires_at`):
```java
@Column(name = "workflow_id", nullable = false)
private String workflowId;

@Column(name = "workflow_version", nullable = false)
private int workflowVersion;
```

**`ApprovalState` stops being a Java enum.** Every place it's currently
typed becomes `String`:
- `ApprovalRequest.state`, `AuditLog.previousState`/`.newState` — plain
  `@Column(name = "state")` / no `@Enumerated`.
- `Transition.from`/`.to`, `WorkflowDefinition.states()` — `String`.
- `ConcurrentStateChangeException.currentState`,
  `InvalidStateTransitionException.currentState`,
  `ApprovalResponseDto.state()`, `ErrorResponseDto.currentState` —
  `String`. JSON wire shape is unchanged (it was already serialized as a
  string); only Java-side typing loosens.
- `ApprovalRequestRepository.guardedTransition(...)`,
  `.findByStateAndExpiresAtBefore(...)` — `String` parameters.

This is mechanical but touches ~10 files — sized as its own task in the
plan, landed and fully green (existing 39 tests passing unchanged in
behavior, just recompiled against `String`) before any new capability is
added on top, so a regression here is never conflated with a genuinely
new bug.

**`PolicySnapshot` changes shape** from one flat record to per-stage:
```java
public record PolicySnapshot(
        String policyVersion,
        Map<String, StagePolicy> stages,   // keyed by state id
        boolean makerCanApprove) {}

public record StagePolicy(int requiredApprovals, List<String> eligibleRoles) {}
```
`makerCanApprove` stays request-level — "maker can never approve their
own request" is a whole-request rule in every example workflow so far;
per-stage maker-exclusion isn't a requirement anyone has asked for, and
YAGNI says don't build it speculatively.

**`ApprovalDecision` gains a `state` column** — which stage the decision
was recorded against:
```java
@Column(name = "state", nullable = false)
private String state;
```
`ApprovalDecisionRepository.countByRequestIdAndDecision(...)` becomes
`countByRequestIdAndDecisionAndState(String requestId, DecisionType d,
String state)` — quorum counting scopes to the current stage, not the
request's entire history. The existing
`existsByRequestIdAndActorId(requestId, actorId)` (decision-level
idempotency, spec §11) stays request-wide on purpose: an actor who
already decided on *this specific stage* replays; if a workflow ever
lets the same actor act again at a *later* stage, that's a new decision
row with a different `state`, not blocked by the unique constraint
(`UNIQUE(request_id, actor_id)` needs to become `UNIQUE(request_id,
actor_id, state)` for this to be correct — noted as a required migration
alongside the new column, not an afterthought).

**`CreateApprovalRequestDto`/`CreateApprovalRequest`**: the flat
`requiredApprovals`/`eligibleRoles` fields become a per-stage map the
caller supplies, matching whatever workflow their `requestType` resolves
to:
```java
public record CreateApprovalRequestDto(
        @NotBlank String requestId,
        @NotBlank String requestType,
        @NotBlank String makerId,
        @NotNull Map<String, StagePolicyDto> stagePolicies,
        boolean makerCanApprove,
        @NotBlank String payloadJson,
        @NotNull Instant expiresAt) {}
```
The caller (e.g. `banking-service`'s `PolicyResolver` for
`TRANSFER_APPROVAL`) needs to know its target workflow's stage ids to
supply matching policy — inherent to "caller resolves policy," not new
coupling beyond what already exists (today's caller already needs to
know the single implicit stage exists).

## 6. Generic command dispatch

`approve`/`reject`/`cancel` (same method signatures, same URL shape)
change from hardcoded literals to:

1. Load request (pessimistic lock, unchanged).
2. Resolve `WorkflowDefinition` via `request.getWorkflowId()` +
   `request.getWorkflowVersion()` from `WorkflowRegistry`.
3. Find the transition named `<action>` whose `from` equals
   `request.getState()`. None found → `classifyRaceOrIllegal` (§7). Found
   → its `guard` and `to` drive everything downstream, replacing every
   hardcoded `ApprovalState.PENDING_APPROVAL`/`.APPROVED` literal in the
   current code.
4. Guard evaluation unchanged in mechanism (`GuardRegistry.get(name)`),
   but every guard that currently reads `ctx.policy().requiredApprovals()`
   or `.eligibleRoles()` directly — `no_approval_required`,
   `approval_required`, `approvals_satisfied`, `actor_is_eligible_checker`,
   all four in `StandardGuards` — now reads
   `policySnapshot.stages().get(currentState)` instead of one flat value.
   `GuardContext` gains a `String currentState` field so every guard has
   access to which stage it's evaluating for, not just the ones that
   happen to need it today.
5. Quorum count: `countByRequestIdAndDecisionAndState(requestId, APPROVE,
   currentState)`.
6. `guardedTransition(requestId, currentState, version, transition.to())`
   — target is the resolved transition's `to`, never a literal.
7. Outbox: after a successful transition, look up
   `workflow.events().get(newState)` — for each event type listed (usually
   zero or one), write an outbox row. Replaces the current hardcoded
   `writeOutbox(requestId, "ApprovalApproved")`-style call sites.

`ExpiryTransitionService.expireOne` generalizes the same way: given a
candidate row, resolve its workflow, find the `expire` transition from
its current state, guarded-transition to that transition's `to`. The
sweeper's candidate query (`findByStateAndExpiresAtBefore`) needs to
become "state is non-terminal for its own workflow" rather than the
current hardcoded `state = PENDING_APPROVAL` — practically: query by
`state IN (<every non-terminal state across every loaded workflow>)`,
computed once at startup from `WorkflowRegistry`, or (simpler, chosen
here) query all non-terminal rows past `expiresAt` and let `expireOne`
itself look up whether an `expire` transition exists from that row's
specific state, no-op if not (some stages may legitimately not offer
expiry).

## 7. `classifyRaceOrIllegal` — generalized, flagged for its own careful pass

The 2-state version distinguishes "genuinely raced" (409
`CONCURRENT_STATE_CHANGE`) from "never legal regardless of timing" (409
`INVALID_STATE_TRANSITION`) by checking whether the current state is
reachable from `PENDING_APPROVAL`, with a `currentVersion <= 1` tiebreak
for the one case where a state is reachable two ways (auto-approve
straight from `SUBMITTED`, or the real approval path) — full reasoning
in the existing code comment (`ApprovalCommandService.java`).

**Generalized principle**: given the transition named `<action>` that
was attempted has no edge from the row's *actual* current state, that
current state is a "race" if it's reachable via *some* legal transition
path from a state where `<action>` *would* have applied — reachability
now a real BFS/DFS over the loaded `WorkflowDefinition`'s transition
graph, not a single-hop check. The version-tiebreak generalizes the same
way it did before: **when more than one path could explain reaching the
current state** (an N-stage graph can have this happen at more than just
one point, unlike the 2-state case), `currentVersion` — which increments
exactly once per transition taken — tells you *how many hops* the row
actually took, which disambiguates "shortcut path" from "the long way
round with a genuine race in it."

This is flagged, not fully specified here, on purpose: getting this
provably right for an arbitrary graph is a real, focused piece of design
work — the 2-state version only reached its current, correct shape after
a dedicated review round caught a real bug in an earlier attempt (spec
history: Task 5's `classifyRaceOrIllegal` fix). The plan gives this its
own task, its own concurrency test suite (mirroring
`ApprovalConcurrencyTest`/`ExpiryVersusApproveConcurrencyTest`, extended
to a 3+-stage workflow, multiple actors racing across different stages),
and its own reviewer pass — not bundled into the generic-dispatch task
where a subtle bug here could hide behind an otherwise-green build.

## 8. UI: generic workflow-view endpoint

New endpoint, additive, doesn't change any existing documented contract:

```
GET /approvals/{id}/workflow-view
```
```json
{
  "workflowId": "privileged-access",
  "workflowVersion": 1,
  "currentState": "MANAGER_APPROVAL",
  "terminalStates": ["APPROVED", "REJECTED", "EXPIRED"],
  "stages": [
    { "id": "SUBMITTED", "label": "Submitted", "status": "COMPLETED" },
    {
      "id": "SECURITY_REVIEW", "label": "Security Review", "status": "COMPLETED",
      "requiredApprovals": 1, "completedApprovals": 1,
      "approvals": [{ "actorId": "sec-1", "actorRole": "SECURITY", "decision": "APPROVE", "createdAt": "..." }]
    },
    {
      "id": "MANAGER_APPROVAL", "label": "Manager Approval", "status": "IN_PROGRESS",
      "requiredApprovals": 1, "completedApprovals": 0, "approvals": []
    },
    { "id": "COMPLIANCE_REVIEW", "label": "Compliance Review", "status": "PENDING" },
    { "id": "APPROVED", "label": "Approved", "status": "PENDING" }
  ]
}
```
`status` per stage: `COMPLETED` (the request passed through and moved on),
`IN_PROGRESS` (current state), `PENDING` (not reached yet), `FAILED`
(a terminal failure state was reached instead of this one — only applies
to the specific terminal state actually landed on). Stage order for
display is the YAML's `states:` list order — fine for every example
workflow here (linear chains with side-branches to terminal failure
states); a workflow with genuine parallel/branching *non-terminal* paths
would need real graph-layout, out of scope.

`banking-service`'s `UiController`/`ui.html` (already built) gets
extended: the proxy layer calls this new endpoint instead of the current
flat `GET /approvals/{id}`, and `ui.html`'s pipeline renderer becomes
data-driven — walks `stages[]` and builds blocks from `label`/`status`,
no hardcoded stage names in JS. The existing three-lifecycle visual
separation (Approval Engine section vs. Transfer Execution section, engine-
boundary marker) stays exactly as already agreed — this endpoint only
covers the Approval Engine's own stages; execution stages keep coming from
`banking-service`'s own `Transfer.state`, unchanged.

Also folded in, per the earlier UI discussion: real `workflow_id`/
`workflow_version` display (now genuinely real per-request data, not
guessed), real `actorId` only (no invented display names), the SLA
countdown computed from the already-persisted `expires_at`, and the
advisory "why can I approve" check computed client-side from the same
data this endpoint already returns (role match, not-maker,
not-already-decided-at-this-stage, within SLA) — explicitly labeled in
the UI as a preview of the server's guards, not the authorization
boundary itself.

## 9. What does NOT change

- The guarded-UPDATE-as-the-only-concurrency-mechanism principle (spec
  §12) — unchanged, just parameterized by state-as-string instead of
  state-as-enum.
- The pessimistic row lock for quorum counting (spec §12 amendment) —
  unchanged, still taken before every approve/reject/cancel.
- Decision-level idempotency via a unique constraint (spec §11) —
  unchanged in mechanism, widened to `(request_id, actor_id, state)`.
- The outbox + polling relay (spec §17) — unchanged; only which events
  fire and when becomes data-driven instead of hardcoded per call site.
- `transfer-approval`'s actual behavior for existing callers — identical
  before and after, expressed as YAML instead of implicit Java literals.
  `banking-service`'s existing `PolicyResolver` output needs reshaping
  into the new per-stage-map DTO shape, but the amount-tier logic itself
  (0/1/2 approvals by threshold) doesn't change.

## 10. Migration note

Existing rows in `approval_request`/`approval_decision` predate
`workflow_id`/`workflow_version`/`ApprovalDecision.state`. Given
`ddl-auto: update` (the project's already-documented, deliberate
schema-management trade-off — no Flyway) and that this is local
dev/test data with no production users, the plan's migration task is:
drop and let Hibernate recreate the schema on next startup, not a
backfill. Calling this out explicitly so it isn't silently assumed away.
