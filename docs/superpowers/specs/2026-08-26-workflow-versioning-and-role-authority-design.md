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
>
> **Mechanism chosen to close both**: `policy_snapshot` embeds the fully
> resolved `WorkflowDefinition` (states, transitions with their
> `allowedRoles`/`requiredApprovals`, terminal states) at request-creation
> time — not just an `(workflowId, version)` pointer. Every subsequent
> operation on that request reads its own row, never the live registry.
> This is a stronger guarantee than a version-keyed registry alone: it
> doesn't depend on old YAML files staying resident in the classpath
> forever for correctness, the same way `policy_snapshot` already doesn't
> depend on anything external today.
>
> **Third reversal, added on review of this doc's first draft**: the
> prior spec's `WorkflowSelector.resolve(requestType)` — an internal
> `requestType → workflowId` mapping living inside approval-engine
> (`workflow-selection.yaml`) — is removed. §1 already states the real
> principle ("the domain's `PolicyResolver` selects which workflow
> definition *and version* applies... the engine receives `workflowId` +
> `workflowVersion` and executes that exact definition"); keeping an
> internal requestType-based selector was a leftover inconsistency with
> that principle, not a deliberate choice. The caller now supplies the
> exact `(workflowId, workflowVersion)` pair directly; the engine does a
> plain existence lookup, never a "pick the best match" resolution.

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

## 2. `WorkflowRegistry` keyed by `(workflowId, workflowVersion)` — used only at creation and startup

Today: `Map<String workflowId, WorkflowDefinition>` — one resident
version per workflow, loaded once at startup from a single mutable YAML
file per `workflowId`. `get(workflowId)` takes no version parameter;
every call site (`approve`/`reject`/`cancel`,
`GET /{id}/workflow-view`) resolves by `workflowId` alone, on every
call — meaning the registry has to keep exactly the right historical
version resident forever for old requests to keep behaving correctly.

Change to a composite key:
```java
public record WorkflowKey(String workflowId, int version) {}
```
`Map<WorkflowKey, WorkflowDefinition>`, loaded by scanning every `*.yaml`
under `workflow/definitions/` as today, but multiple versions of the
same `workflowId` now coexist as separate files (e.g.
`transfer-high-value-v1.yaml`, `transfer-high-value-v2.yaml`) rather
than one file being overwritten in place. Startup fails if:
- YAML is invalid.
- `(workflowId, version)` is duplicated across files.
- `initialState` doesn't name a declared state.
- any `terminalStates` entry doesn't name a declared state.
- any transition's `from`/`to` doesn't name a declared state.
- any transition's `guard` doesn't resolve in `GuardRegistry`.
- (existing check, unchanged) a state has outgoing transitions XOR it's
  declared terminal.

**But per §4 below, the registry is only ever consulted at two moments:**
1. **Request creation** — the caller (e.g. `banking-service`'s
   `PolicyResolver`) supplies the exact `workflowId` + `workflowVersion`
   it wants (§1); `ApprovalCommandService.create()` calls
   `workflowRegistry.get(workflowId, workflowVersion)`, which throws
   `InvalidRequestException` if that exact pair was never loaded. No
   "latest" resolution, no `requestType`-keyed indirection inside the
   engine — the engine performs an existence check, not a policy
   decision. The resolved definition gets embedded into the new
   request's `policy_snapshot` (§4) — that's the one and only read.
2. **Startup validation** (above).

There's no `resolveLatest`/"current version" concept anywhere in the
engine. If a domain wants "new requests should move to the newest
version automatically," that's the domain's own `PolicyResolver` config
to change — a business decision explicitly out of the engine's hands,
consistent with §1's "engine stays domain-blind."

Every operation on an *existing* request (`approve`/`reject`/`cancel`,
`workflow-view`, `classifyRaceOrIllegal`) reads
`request.getPolicySnapshot().workflow()` — never
`workflowRegistry.get(...)`. This means version-keying the registry is
still correct and worth doing (a new request must never accidentally
resolve to a stale in-memory reference to an old version, and startup
validation needs every version's shape checked), but it's no longer the
thing standing between old requests and correct behavior — the embedded
snapshot is. Even if a historical YAML file were later deleted from the
classpath entirely, requests already created against it keep working
unaffected, because nothing at runtime looks it up again.

**Relational identity vs. runtime authority, made explicit**:
`ApprovalRequest.workflowId`/`.workflowVersion` and
`policy_snapshot.workflow.name()`/`.version()` hold the same two values,
written atomically in the same `create()` transaction — they can never
disagree by construction. The columns exist for plain relational
use (indexing, filtering, listing requests without parsing jsonb — e.g.
the history-list UI); the embedded `workflow` is what every
command-execution code path actually reads. Not two competing sources
of truth — one value, one authoritative reader, one denormalized-for-
convenience copy.

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
  allowedRoles: [RISK_CHECKER]
- name: cancel
  from: RISK_REVIEW
  to: CANCELLED
  guards: [actor_is_maker]
```
`allowedRoles` is OR-matched (any one qualifies). Transitions with no
human actor (`route`/`expire`, driven by `ExpiryTransitionService` or
the initial-routing guard, never reachable through the actor-facing
`approve`/`reject`/`cancel` endpoints) carry no `allowedRoles` — there's
no generic "invoke any transition by name" endpoint today, so nothing
new is needed to keep these unreachable by a human caller.

`Transition` gains two fields, and `guard` generalizes to `guards` (see
below):
```java
public record Transition(String name, String from, String to, List<String> guards,
                          List<String> allowedRoles, Integer requiredApprovals) {}
```
(`requiredApprovals` nullable — only quorum-bearing transitions carry
it; if present at all, it must be `>= 1`, checked at startup alongside
the other structural validations in §2 — a `requiredApprovals: 0` or
negative value is a config error, not a runtime "no approvals needed"
signal (`approval_required`/`no_approval_required` already own that
routing decision at creation).)

**Role-matching becomes an intrinsic dispatch step, not a guard.**
Reviewing the first draft: it kept `actor_is_eligible_checker` as an
explicit, opt-in guard reading `allowedRoles` — meaning a workflow
author could set `allowedRoles` on a transition, forget to also attach
the guard, and silently allow any role through. Since `allowedRoles` is
now structural data on the transition itself (not caller policy), the
engine enforces it unconditionally whenever a transition declares a
non-empty `allowedRoles`: after resolving the matched transition and
before evaluating its `guard`, check `actorRole ∈
transition.allowedRoles()`; forbidden if not. `actor_is_eligible_checker`
is deleted from `StandardGuards` — dead code, nothing left needs it. This
isn't a redesign of the guard concept; it recognizes that role-matching
was already fully determined by the transition once `allowedRoles`
existed, so making it opt-in was a footgun, not flexibility.

**Maker self-approval moves to a declarative guard, not caller data.**
The prior draft kept `makerCanApprove` in `PolicySnapshot` as a
caller-supplied per-request flag. On review: that's inconsistent with
this whole design's point — if a caller can no longer invent
`eligibleRoles` per request, it shouldn't be able to invent "can the
maker approve" per request either. Whether a *workflow* permits
self-approval is a property of that workflow, not of who happens to
submit a given request. New guard, `actor_is_not_maker`
(`ctx.actorId() != ctx.makerId()`), attached only to the approve
transitions of workflows that want to forbid it:
```yaml
- name: approve
  from: PENDING_APPROVAL
  to: APPROVED
  guard: approvals_satisfied
  allowedRoles: [TRANSFER_CHECKER]
```
becomes, where self-approval must be blocked:
```yaml
- name: approve
  from: PENDING_APPROVAL
  to: APPROVED
  guards: [approvals_satisfied, actor_is_not_maker]
  allowedRoles: [TRANSFER_CHECKER]
```
This requires one narrowly-scoped mechanical change: `Transition.guard`
(singular `String`) becomes `Transition.guards` (`List<String>`,
AND-composed — every named guard must pass; an empty/absent list always
passes). Every existing transition's YAML `guard: X` becomes `guards:
[X]` — mechanical, no behavior change for the single-guard case, which
is still the overwhelming majority. This is still "the existing fixed
guard registry approach... not an expression engine" (§14 of the
original request) — just composition of named guards by AND, not
arbitrary boolean expressions. A workflow that wants to *permit*
self-approval simply omits `actor_is_not_maker` from its `guards` list.
`PolicySnapshot.makerCanApprove` is removed entirely (§4).

**Not touched, and deliberately not collapsed into `allowedRoles`**:
cancel's `guard: actor_is_maker` stays a guard, not a role check.
`actor_is_maker` compares `actorId` against `request.getMakerId()` — an
*identity* check, unrelated to the actor's role. `MAKER` isn't a role in
this system's vocabulary (`TRANSFER_CHECKER`, `RISK_CHECKER`,
`TRANSFER_MANAGER`, `COMPLIANCE_OFFICER` are); collapsing an identity
check into `allowedRoles: [MAKER]` would require inventing a synthetic
pseudo-role and re-deriving the real check (`actorId == makerId`) from
it anyway, which is more indirection for no gain. `actor_is_not_maker`
above is the same reasoning applied to approve: still an identity guard,
just negated, not a role list.

**What stays a guard**: `approvals_satisfied` (quorum count),
`sla_expired` (time), `approval_required`/`no_approval_required`
(amount-based routing at creation), `actor_is_maker`/`actor_is_not_maker`
(identity) — none of these are role-eligibility checks, and all keep
working exactly as today (mechanism-wise). Guards remain a general
"named condition that must pass" mechanism; role-matching is no longer
modeled as one, because it's no longer optional.

## 4. `PolicySnapshot` embeds the resolved `WorkflowDefinition`

```java
public record PolicySnapshot(String policyVersion, WorkflowDefinition workflow) {}
```
`StagePolicy`, the `stages` map, and `makerCanApprove` are all removed —
no separate type needed, and no caller-set behavioral flags survive
outside `workflow`. `workflow` is a direct, immutable copy of the
`WorkflowDefinition` (§3's extended `Transition`, `states`,
`initialState`, `terminalStates`, `events`) resolved by an exact
`workflowRegistry.get(workflowId, workflowVersion)` lookup at creation
time (§2), stored as-is inside the same jsonb column that already holds
`policy_snapshot` today (`WorkflowDefinition` is already a plain
nested-record type, so no new Jackson handling beyond what
`PolicySnapshotConverter` already does). `requiredApprovals`/
`eligibleRoles`/self-approval permission no longer exist as separate
caller input at all — they're whatever `workflow.transitions()` says,
frozen at creation. `policyVersion` is the one surviving free-form field:
a caller-supplied label for the domain's *own* policy-configuration
version (e.g. `PolicyResolver`'s amount-tier logic) — orthogonal to
`workflowVersion`, useful for audit correlation, never read by any
authorization or transition logic.

**`policy_snapshot` is configuration only, never runtime state.** It
holds: states, transitions (`guards`, `allowedRoles`,
`requiredApprovals`), terminal states, events, workflow name/version. It
never holds: `currentState`, approval decisions, completed-approval
counts, actor identities, timestamps, or execution status — those stay
exactly where they already live, in `ApprovalRequest.state`,
`approval_decision`, and `audit_log`. Worth stating explicitly since
it'd be an easy, hard-to-notice mistake to let read-time-computed data
leak into what's supposed to be a frozen, purely-declarative snapshot.

`CreateApprovalRequestDto`/`CreateApprovalRequest` lose `stagePolicies`
and `makerCanApprove` entirely — the caller supplies `workflowId`,
`workflowVersion` (both required, exact — §1/§2), `requestType` (kept as
a descriptive/audit field only, no longer used for resolution — see the
third reversal above), `makerId`, `payload`, and `expiresAt`. No
per-stage map to build or keep in sync with the workflow's real shape;
`ApprovalCommandService.create()` builds the `PolicySnapshot` itself
(embedding the looked-up `WorkflowDefinition`), rather than receiving
one ready-made from the caller as it does today.

`ApprovalRequest.workflowId`/`workflowVersion` columns stay — see §2's
"relational identity vs. runtime authority" — populated from the same
lookup result used to build `policy_snapshot.workflow`, in the same
transaction.

`banking-service`'s `PolicyResolver` changes shape to match §1: instead
of returning `ApprovalPolicy(requiredApprovals, eligibleRoles,
makerCanApprove)`, it returns a `WorkflowSelection(workflowId,
workflowVersion)` chosen by amount tier — e.g. today's single
`transfer-approval` workflow becomes two: `transfer-standard` (0 or 1
approvals, `TRANSFER_CHECKER`, self-approval permitted for the
auto-release case) and `transfer-high-value` (2 approvals,
`actor_is_not_maker` attached) as separate YAML files, replacing the
current in-Java tier `if/else` that hardcodes the role string and
approval counts. Only the *amount thresholds* stay configurable Java
values (`@Value`); which workflow, version, and its role/quorum/
self-approval shape is now data, not code — and it's a fixed,
`PolicyResolver`-owned mapping to a specific version, never "whatever's
newest."

## 5. Approval-decision uniqueness, unchanged in shape

`(request_id, actor_id, state)` — already the constraint after the prior
spec's per-stage decision work. No change here; called out only to
confirm this design doesn't touch it.

## 6. Generic transition engine — sourced from the row, not the registry; role-check now intrinsic

`currentState + command → WorkflowDefinition.transitionsFrom(state) →
matching transition → validate actor/guard → guardedTransition(...,
transition.to())` — this is already how `approve`/`reject`/`cancel`
work post-prior-spec, with two additions from §3: after the matching
transition is found and before its `guards` are evaluated, if
`transition.allowedRoles()` is non-empty the engine checks `actorRole ∈
allowedRoles` unconditionally (no longer a named, opt-in guard); then
each name in `transition.guards()` is evaluated in order, AND-composed —
any failure stops the transition (for approve specifically, an
`approvals_satisfied` failure means "stay put, record the decision,
don't transition," same as today; any other guard failing is a hard
`ForbiddenActionException`/`InvalidStateTransitionException`, also
same as today). The other change: `workflowFor(request)` becomes
```java
private WorkflowDefinition workflowFor(ApprovalRequest request) {
    return request.getPolicySnapshot().workflow();
}
```
replacing `workflowRegistry.get(request.getWorkflowId())`. `WorkflowRegistry`
is only ever called once now, inside `create()`, for the exact-match
lookup described in §2 — `approve`/`reject`/`cancel`/`workflow-view`
never inject or call it. `WorkflowSelector` and
`workflow-selection.yaml` are deleted (the intro's third reversal) —
there's no indirection left to remove them from. No new dispatch mechanism, no
merging the three endpoints into one generic `POST /{id}/{action}` —
preserves the existing REST structure per the prior spec's own choice.

`classifyRaceOrIllegal`'s BFS-over-the-graph approach (already
generalized, per the most recent commit) is unaffected — it operates on
whichever `WorkflowDefinition` it's handed; that's now
`request.getPolicySnapshot().workflow()` instead of a registry lookup,
same object shape either way.

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
change and `Transition`'s new/renamed fields mean: drop and let
Hibernate recreate on next startup, not a backfill. The existing
`transfer-approval.yaml`/`privileged-access.yaml` need, mechanically, in
the same change:
- `guard: X` → `guards: [X]` on every transition.
- `allowedRoles`/`requiredApprovals` added, using today's
  already-known-correct values (`TRANSFER_CHECKER` /
  `SECURITY`/`MANAGER`/`COMPLIANCE`-shaped roles per the existing tests'
  fixtures).
- `actor_is_not_maker` added to `transfer-approval`'s approve
  transition's `guards` list (today's `makerCanApprove` behavior for
  transfers is "maker cannot approve," per `ApprovalCommandService`'s
  existing check) — `privileged-access` likewise, unless its tests show
  otherwise.
- `workflow-selection.yaml` and `WorkflowSelector` deleted; whatever test
  fixtures / `banking-service` calls relied on `requestType`-based
  resolution switch to supplying `workflowId`+`workflowVersion` directly.

## 10. Tests required (new/changed, beyond what the prior spec already covers)

- **Registry versioning**: two YAML files for the same `workflowId` at
  different versions both load; a request created under v1 keeps
  resolving v1's transitions/roles after v2 is loaded; duplicate
  `(workflowId, version)` across files fails startup.
- **Exact-version lookup, no "latest"**: creating a request with a
  `workflowVersion` that was never loaded fails with a clear error, even
  when a different version of the same `workflowId` does exist —
  confirms there's no silent fallback to "whatever's newest."
- **Snapshot self-containment**: create a request, then remove/corrupt
  its workflow's YAML file (or swap in a mock `WorkflowRegistry` that
  throws) and confirm `approve`/`reject`/`cancel`/`workflow-view` all
  still work correctly against that request — proves the runtime path
  genuinely never re-consults the registry post-creation.
- **Role authority, intrinsic enforcement**: `allowedRoles` from the
  transition (not caller JSON) is what's enforced, unconditionally,
  even on a transition whose `guards` list is empty; an actor whose role
  isn't listed is forbidden even if the (now-removed) caller-supplied
  policy would have allowed it — confirms there's no remaining code path
  that lets a caller inject roles, and no way to accidentally ship a
  transition with `allowedRoles` set but unenforced.
- **`guards` AND-composition**: a transition with
  `guards: [approvals_satisfied, actor_is_not_maker]` requires both to
  pass; maker attempting to approve with quorum already satisfied is
  still forbidden by `actor_is_not_maker`; a non-maker checker succeeds
  once `approvals_satisfied` is also true.
- **`actor_is_not_maker` opt-in**: a workflow whose approve transition
  omits it allows the maker to approve their own request (role
  permitting); one that includes it doesn't — replaces the old
  `makerCanApprove` boolean test.
- **`PolicySnapshot` shape**: create-request validation no longer
  accepts `stagePolicies` or `makerCanApprove`; requires `workflowId` +
  `workflowVersion` explicitly.
- **`requiredApprovals` structural validation**: a workflow YAML with
  `requiredApprovals: 0` or negative fails at startup, not at first use.
- **`availableActions`**: workflow-view returns the correct action list
  with roles/quorum per stage, including the in-progress
  `currentApprovals` count.
- **Existing 39+ tests** (prior spec's suite): pass unchanged in
  observable behavior — this is a refactor of *where* role/quorum data
  lives, not a behavior change for `transfer-standard`'s or
  `privileged-access`'s existing test scenarios.
