# Workflow Versioning and Role Authority — Design

> Extends `2026-08-26-multi-stage-workflow-engine-design.md` ("the prior
> spec"). That spec deliberately deferred two things as explicit
> out-of-scope cuts:
> - §2: "Concurrent multi-version execution... one active version per
>   named workflow at a time... an in-flight request created under a
>   version that gets replaced mid-flight is a known, accepted gap."
> - §5/§3: `eligibleRoles`/`requiredApprovals` live in caller-supplied
>   `policy_snapshot`, never in the workflow YAML — "the workflow YAML
>   only ever defines *shape*... never *quantities*."
>
> This spec reverses both, based on gaps found reviewing the implemented
> system: (1) `WorkflowRegistry` is keyed by name only, so
> `ApprovalRequest.workflowVersion` is persisted but never read back —
> redeploying a workflow YAML retroactively changes behavior for every
> existing request against that workflow, including ones created under
> an earlier version; (2) `eligibleRoles` is pure caller-supplied JSON
> with zero server-side authority or validation — two requests under the
> same workflow can carry different eligible roles for the same stage,
> and a typo on either side (the JSON or the actor's role on a decision
> call) silently makes a stage permanently unapprovable with no
> validation catching it.

## 1. Core architectural decision

`PolicyResolver` (in the domain, e.g. `banking-service`) selects **which
workflow definition and version** applies to a request — not individual
approval counts. Example:

```
Transfer ₹1L–₹10L  → transfer-standard:v1
Transfer > ₹10L    → transfer-high-value:v1
```

The Approval Engine receives `workflowId` + `workflowVersion` and
executes that exact definition. The engine stays domain-blind: it never
computes amount thresholds.

## 2. `WorkflowRegistry` keyed by `(workflowId, workflowVersion)`

Today: `Map<String workflowId, WorkflowDefinition>` — one resident
version per workflow, loaded once at startup from a single mutable YAML
file per `workflowId`. `get(workflowId)` takes no version parameter;
every call site (`approve`/`reject`/`cancel`,
`GET /{id}/workflow-view`) resolves by `workflowId` alone.

Change to a composite key:
```java
public record WorkflowKey(String workflowId, int version) {}
```
`Map<WorkflowKey, WorkflowDefinition>`, loaded by scanning every `*.yaml`
under `workflow/definitions/` as today, but multiple versions of the
same `workflowId` now coexist as separate files (e.g.
`transfer-high-value-v1.yaml`, `transfer-high-value-v2.yaml`) rather
than one file being overwritten in place.

`get(String workflowId, int version)` replaces `get(String workflowId)`
everywhere:
- `ApprovalCommandService.workflowFor(request)` resolves
  `(request.getWorkflowId(), request.getWorkflowVersion())` —
  `workflowVersion` stops being write-only.
- `ApprovalController.workflowView()` — same.
- `WorkflowSelector.resolve(requestType)` (picks a workflow for a new
  request) resolves to the **latest** version registered for that
  `workflowId` — `WorkflowRegistry` computes `Map<String workflowId, int
  latestVersion>` once at startup (max version seen per id) and exposes
  `resolveLatest(workflowId) → WorkflowDefinition`, used only at
  request-creation time. Once a request is created, it's pinned to that
  specific `(id, version)` forever (§2's immutability rule) — this
  "latest" concept only ever matters for choosing what a *new* request
  starts on.

**Immutability rule**: a loaded `WorkflowDefinition` is never mutated in
place. Publishing a new version means adding a new file
(`transfer-high-value-v2.yaml`) with `version: 2`; the old file and its
definition stay resident and resolvable for any request still pinned to
v1. Startup fails if:
- YAML is invalid.
- `(workflowId, version)` is duplicated across files.
- `initialState` doesn't name a declared state.
- any `terminalStates` entry doesn't name a declared state.
- any transition's `from`/`to` doesn't name a declared state.
- any transition's `guard` doesn't resolve in `GuardRegistry`.
- (existing check, unchanged) a state has outgoing transitions XOR it's
  declared terminal.

This closes the gap directly: `WorkflowRegistry` becomes as
immutable-safe as `policy_snapshot` already is — an existing request
keeps resolving against the exact graph it was created under, forever,
even after a new version is published.

## 3. Roles and required-approvals move into the transition

Today, `StagePolicy(requiredApprovals, eligibleRoles)` lives entirely in
caller-supplied `policy_snapshot.stages[stageId]` — the workflow YAML
never mentions roles at all. This is the actual authority gap: nothing
stops two requests on the same workflow from disagreeing about who can
approve `SECURITY_REVIEW`.

New transition shape:
```yaml
- name: approve
  from: RISK_REVIEW
  to: MANAGER_APPROVAL
  guard: approvals_satisfied
  allowedRoles: [RISK_CHECKER]
  requiredApprovals: 1
- name: reject
  from: RISK_REVIEW
  to: REJECTED
  guard: actor_is_eligible_checker
  allowedRoles: [RISK_CHECKER]
- name: cancel
  from: RISK_REVIEW
  to: CANCELLED
  guard: actor_is_maker
```
`allowedRoles` is OR-matched (any one qualifies). Transitions with no
human actor (`route`/`expire`, driven by `ExpiryTransitionService` or
the initial-routing guard, never reachable through the actor-facing
`approve`/`reject`/`cancel` endpoints) carry no `allowedRoles` — there's
no generic "invoke any transition by name" endpoint today, so nothing
new is needed to keep these unreachable by a human caller.

`Transition` gains two fields:
```java
public record Transition(String name, String from, String to, String guard,
                          List<String> allowedRoles, Integer requiredApprovals) {}
```
(`requiredApprovals` nullable — only approve-type transitions carry it;
reject/cancel don't need a quorum.)

**Guard changes**: `actor_is_eligible_checker` and `approvals_satisfied`
(in `StandardGuards`) stop reading
`policySnapshot.stages().get(currentState)` and instead read the
matched `Transition`'s own `allowedRoles`/`requiredApprovals`, passed
into `GuardContext` alongside the fields it already carries
(`actorRole`, decision count, `currentState`). Guard *names* and the
guard-registry mechanism are unchanged — this only changes where each
guard reads its role/quorum data from.

**What stays a guard, not a role check**: `approvals_satisfied` (quorum
count), `sla_expired` (time), `approval_required`/`no_approval_required`
(amount-based routing at creation) — these have nothing to do with
identity and keep working exactly as today. Guards remain a general
"named condition that must pass" mechanism; role-matching is one guard
among several, not a redesign of the concept.

## 4. `PolicySnapshot` shrinks to what's genuinely per-request

```java
public record PolicySnapshot(String policyVersion, boolean makerCanApprove) {}
```
`StagePolicy` and the `stages` map are removed entirely.
`requiredApprovals`/`eligibleRoles` no longer exist as caller input —
they're whatever the selected workflow's transitions say, full stop.

`makerCanApprove` is the one field that survives: it's an identity check
("the maker can't approve their own request even if their actor role
happens to match `allowedRoles`"), not a role-eligibility check —
`allowedRoles` can't express it, and it's a genuinely per-request,
caller-set flag (a workflow might allow self-approval for
low-friction/low-risk request types). `actor_is_maker` combined with
this flag, exactly as implemented today
(`ApprovalCommandService.java:165`), is unchanged.

`CreateApprovalRequestDto`/`CreateApprovalRequest` lose `stagePolicies`
entirely — the caller supplies `workflowId` (or a `requestType` the
`WorkflowSelector` maps to one), `makerId`, `payload`, `expiresAt`, and
`makerCanApprove`. No per-stage map to build or keep in sync with the
workflow's real shape.

`banking-service`'s `PolicyResolver` changes shape to match §1: instead
of returning `ApprovalPolicy(requiredApprovals, eligibleRoles,
makerCanApprove)`, it returns a `WorkflowSelection(workflowId,
workflowVersion, makerCanApprove)` chosen by amount tier — e.g. today's
single `transfer-approval` workflow becomes two:
`transfer-standard` (0 or 1 approvals, `TRANSFER_CHECKER`) and
`transfer-high-value` (2 approvals) as separate YAML files, replacing
the current in-Java tier `if/else` that hardcodes the role string and
approval counts. Only the *amount thresholds* stay configurable Java
values (`@Value`); which workflow and its role/quorum shape is now data,
not code.

## 5. Approval-decision uniqueness, unchanged in shape

`(request_id, actor_id, state)` — already the constraint after the prior
spec's per-stage decision work. No change here; called out only to
confirm this design doesn't touch it.

## 6. Generic transition engine, unchanged in shape

`currentState + command → WorkflowDefinition.transitionsFrom(state) →
matching transition → validate actor/guard → guardedTransition(...,
transition.to())` — this is already how `approve`/`reject`/`cancel`
work post-prior-spec. This design only changes *what data* the guard
step reads (§3) and *which* `WorkflowDefinition` gets resolved (§2). No
new dispatch mechanism, no merging the three endpoints into one generic
`POST /{id}/{action}` — preserves the existing REST structure per the
prior spec's own choice.

`classifyRaceOrIllegal`'s BFS-over-the-graph approach (already
generalized, per the most recent commit) is unaffected — it operates on
whichever single `WorkflowDefinition` gets resolved for the request; the
version-keyed registry just changes *which* definition that is, not how
the classification logic works once it has one.

## 7. Workflow-view: `availableActions`

Extend the existing `GET /{id}/workflow-view` response (additive, same
endpoint) with a top-level `availableActions` array, each entry the
`name`/`allowedRoles`/`requiredApprovals`/`currentApprovals` of every
transition reachable from `currentState`:
```json
{
  "workflowId": "transfer-high-value",
  "workflowVersion": 1,
  "currentState": "RISK_REVIEW",
  "availableActions": [
    { "name": "approve", "allowedRoles": ["RISK_CHECKER"], "requiredApprovals": 1, "currentApprovals": 0 },
    { "name": "reject", "allowedRoles": ["RISK_CHECKER"] },
    { "name": "cancel", "allowedRoles": ["MAKER"] }
  ],
  "stages": [ ... unchanged from prior spec ... ]
}
```
`ui.html` uses this to show/hide Approve/Reject/Cancel buttons by
matching the console's `actorRole` field against each action's
`allowedRoles` — **advisory only**, explicitly not the authorization
boundary. The command endpoints (`approve`/`reject`/`cancel`)
independently re-validate role/state/guard server-side regardless of
what the UI showed, exactly as they do today.

## 8. What does NOT change

- Optimistic concurrency (`guardedTransition`, version-checked UPDATE) —
  unchanged.
- Pessimistic row lock for quorum counting — unchanged.
- Outbox + polling relay, event-per-state-on-entry mechanism — unchanged.
- Audit log — unchanged; still append-only, still the source stage
  entry/exit timestamps are derived from (no `stage_instance` table —
  see the earlier schema-review conversation, same conclusion holds).
- `transfer-approval`'s and `privileged-access`'s actual runtime
  behavior for any *existing* request — identical; this only changes how
  new requests' policy is authored and where the engine reads it from.

## 9. Migration note

Same situation as the prior spec's §10: `ddl-auto: update`, local
dev/test data only, no production users. `policy_snapshot`'s shape
change and `Transition`'s new fields mean: drop and let Hibernate
recreate on next startup, not a backfill. The existing
`transfer-approval.yaml`/`privileged-access.yaml` need `allowedRoles`/
`requiredApprovals` added to their transitions (mechanical, using
today's already-known-correct values: `TRANSFER_CHECKER` /
`SECURITY`/`MANAGER`/`COMPLIANCE`-shaped roles per the existing tests'
fixtures) as part of the same change, not a follow-up.

## 10. Tests required (new/changed, beyond what the prior spec already covers)

- **Registry versioning**: two YAML files for the same `workflowId` at
  different versions both load; a request created under v1 keeps
  resolving v1's transitions/roles after v2 is loaded; duplicate
  `(workflowId, version)` across files fails startup.
- **Role authority**: `allowedRoles` from the transition (not caller
  JSON) is what's enforced; an actor whose role isn't listed is
  forbidden even if the (now-removed) caller-supplied policy would have
  allowed it — i.e. confirm there's no remaining code path that lets a
  caller inject roles.
- **`PolicySnapshot` shape**: create-request validation no longer
  requires/accepts `stagePolicies`; `makerCanApprove` still gates
  self-approval exactly as before.
- **`availableActions`**: workflow-view returns the correct action list
  with roles/quorum per stage, including the in-progress
  `currentApprovals` count.
- **Existing 39+ tests** (prior spec's suite): pass unchanged in
  observable behavior — this is a refactor of *where* role/quorum data
  lives, not a behavior change for `transfer-standard`'s or
  `privileged-access`'s existing test scenarios.
