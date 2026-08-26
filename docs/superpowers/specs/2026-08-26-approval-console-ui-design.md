# Approval Console UI — Design

> Purely additive: no data model changes, no changes to any existing
> endpoint's behavior. Adds two new read endpoints to `approval-engine`
> and a new 3-screen static UI. The existing `banking-service/static/
> ui.html` dev console (submit a transfer, watch it live via SSE) is
> untouched — it stays the "create and watch one transfer" tool. This is
> a separate, additive "browse and act on anything" console.

## 1. Why, and what's explicitly out

Per the user's own scoping: this exists to prove the engine is generic
and YAML-driven, not to be a banking portal. Three screens only —
Requests list, Request Detail (the one that matters most), Workflow
Definitions. No auth, no account management, no transaction-creation UI,
no workflow editor. An actor switcher (a plain dropdown, no real auth)
demonstrates `allowedRoles` visibly changing what's clickable — while
the command endpoints remain the actual authorization boundary
regardless of what the UI shows, exactly as `workflow-view`'s
`availableActions` already documents itself as advisory-only.

## 2. Hosting decision

The new console is served from **`approval-engine`** itself
(`approval-engine/src/main/resources/static/console.html`), not
`banking-service`. Reasoning: Requests-list and Workflow-definitions are
approval-engine's own native resources — serving the console from
approval-engine means it calls its own REST API same-origin, no proxy
layer, no CORS setup, no new banking-service code at all. Transfer
*creation* stays exactly where it already lives — banking-service's
existing `ui.html` — since creating a transfer is a banking-service
concern (validates balance, calls approval-engine, tracks release), not
an approval-engine one. The two tools serve different moments: submit-
and-watch-one (banking-service) vs. browse-and-act-on-any (approval-
engine). A request created via banking-service's flow is immediately
visible in the new console — it's reading the same `approval_request`
table either way.

## 3. New endpoint: `GET /approvals` (list)

Collection GET on the existing `/approvals` base path (`ApprovalController`
already owns `POST /approvals` and `GET /approvals/{id}`; this is the
natural sibling). No pagination — this is a demo dataset, not a
production-scale one; YAGNI.

```
GET /approvals?status=pending|completed|all   (default: all)
```

Response: `List<ApprovalRequestSummaryDto>`, newest (`createdAt`) first.
```java
public record ApprovalRequestSummaryDto(
        String requestId, String workflowId, int workflowVersion,
        String currentState, String currentStageLabel, boolean terminal,
        Integer requiredApprovals, Integer currentApprovals,
        Instant createdAt) {}
```
- `terminal` is `workflow.isTerminal(currentState)` off the request's own
  `policy_snapshot.workflow` — no registry lookup, same self-containment
  principle as every other post-creation read.
- `requiredApprovals`/`currentApprovals` mirror `workflow-view`'s stage
  computation for `currentState` specifically: the `requiredApprovals` of
  the `approve` transition FROM `currentState` if one exists, else
  `null`, with `currentApprovals` from
  `ApprovalDecisionRepository.countByRequestIdAndDecisionAndState`. One
  decision-count query per row — acceptable for a demo-scale list, not
  worth a batched query for this scope.
- `status=pending` filters to `!terminal`, `status=completed` filters to
  `terminal`, `status=all` (default) returns everything. Filtering happens
  in the service/controller layer over the full result set (`ORDER BY
  created_at DESC`, no `WHERE` pushdown needed at this data scale).
- New repository method: `ApprovalRequestRepository.findAllByOrderByCreatedAtDesc()`.

`ApprovalController` gains:
```java
@GetMapping
public List<ApprovalRequestSummaryDto> list(@RequestParam(required = false, defaultValue = "all") String status) {
    List<ApprovalRequest> all = requests.findAllByOrderByCreatedAtDesc();
    return all.stream()
            .map(this::toSummary)
            .filter(s -> switch (status) {
                case "pending" -> !s.terminal();
                case "completed" -> s.terminal();
                default -> true;
            })
            .toList();
}
```
`toSummary(ApprovalRequest)` reuses the same `approveFromHere`-lookup
logic already factored out in `buildStageView` for `workflow-view` — no
new stage-computation logic, just applied to `currentState` alone
instead of every state.

## 4. New endpoints: `GET /workflows`, `GET /workflows/{workflowId}/{version}`

New `WorkflowController`, backed directly by the existing
`WorkflowRegistry` bean (already version-keyed, already loaded at
startup — this just exposes it read-only over HTTP for the first time).

```java
@RestController
@RequestMapping("/workflows")
public class WorkflowController {
    private final WorkflowRegistry registry;
    // constructor …

    @GetMapping
    public List<WorkflowSummaryDto> list() {
        return registry.all().stream()
                .map(d -> new WorkflowSummaryDto(d.name(), d.version(), d.states().size()))
                .toList();
    }

    @GetMapping("/{workflowId}/{version}")
    public WorkflowDefinitionDto get(@PathVariable String workflowId, @PathVariable int version) {
        return toDto(registry.get(workflowId, version)); // IllegalStateException -> 404, same mapping style as ApprovalRequestNotFoundException
    }
}
```
```java
public record WorkflowSummaryDto(String workflowId, int version, int stateCount) {}

public record WorkflowDefinitionDto(String workflowId, int version, String initialState,
                                     List<String> terminalStates, List<WorkflowStateDto> states,
                                     List<WorkflowTransitionDto> transitions) {}
public record WorkflowStateDto(String id, String label) {}
public record WorkflowTransitionDto(String name, String from, String to, List<String> guards,
                                     List<String> allowedRoles, Integer requiredApprovals) {}
```
`registry.get(workflowId, version)` throwing `IllegalStateException` on
an unknown pair needs the same 404 mapping the rest of the controller
layer already has for `ApprovalRequestNotFoundException` — reuse
whatever `@ExceptionHandler`/`@ControllerAdvice` already exists for that
mapping pattern (confirm the exact mechanism when implementing; not
re-derived here since it's existing, unrelated infrastructure).

## 5. UI structure

```
approval-engine/src/main/resources/static/
├── console.html          -- shell: header (title + actor dropdown), left nav, right content area
├── console.css
├── console.js             -- tiny hash-based view switcher (#requests / #requests/{id} / #workflows / #workflows/{id}/{v}), no router library
└── console-api.js         -- fetch wrappers for the 5 endpoints this UI calls
```
No build step, no framework — plain `<script>` tags, matching the
existing `ui.html`'s own style. Client-side "page" switching is
`location.hash` + a `render()` dispatch, not a real router; each page is
a function that replaces `#content`'s `innerHTML`. No frontend state
machine — every page re-fetches fresh from the API on navigation; the
backend is the only state machine, per the user's own stated principle.

**Actor dropdown** (`MAKER`, `TRANSFER_CHECKER`, `RISK_CHECKER`,
`TRANSFER_MANAGER`, `COMPLIANCE_OFFICER`, `SECURITY`, `MANAGER`,
`COMPLIANCE` — the full set of role strings that appear across the
existing workflow YAMLs) is stored in `localStorage` so it survives
navigation between screens without a server round-trip; purely a display
convenience, never sent as an auth token — every `POST .../approve` etc.
call still requires the operator to also type an `actorId`, exactly as
`ui.html` already does, and the server independently re-validates role
regardless.

### Screen 1 — Requests (`#requests`, default view)

Calls `GET /approvals?status=…`. Renders the `[All] [Pending]
[Completed]` tabs (just three links that change `status` and re-fetch)
and a table: Request | Workflow | Current Stage | Approval (X/Y or —) |
Status. Each row links to `#requests/{id}`.

### Screen 2 — Request Detail (`#requests/{id}`)

Calls `GET /approvals/{id}/workflow-view` (existing endpoint, already
returns `stages` and `availableActions` — nothing new needed here). Renders:
- Header: workflowId:version, requestId.
- A horizontal pipeline (reusing the same visual language `ui.html`
  already has for its vertical stage list, just laid out left-to-right
  per the user's mockup: `✓`/`●`/`○` per stage, `X / Y` under any stage
  that has `requiredApprovals`).
- Current stage detail: label, required/current approvals, the list of
  actors who've decided so far (from that stage's `approvals[]`).
- Action buttons (Approve/Reject/Cancel), shown/hidden by matching
  `availableActions[].allowedRoles` against the actor dropdown's current
  value — same `updateActionButtons`-style logic already specified for
  `ui.html` in the existing plan's Task 4, reimplemented here since this
  is a separate HTML file. Clicking Approve/Reject prompts for an
  `actorId` (a plain `prompt()`, no form needed for a demo tool), then
  `POST`s to the existing `/approvals/{id}/{action}` endpoints and
  re-fetches `workflow-view`.

### Screen 3 — Workflow Definitions (`#workflows`, `#workflows/{id}/{version}`)

List screen calls `GET /workflows`: table of workflowId | version |
state count, each row linking to the detail hash. Detail screen calls
`GET /workflows/{id}/{version}`: renders the state chain (`states[]` in
order, arrows between them per the mockup) and, per state, every
transition FROM it (name → to, guards, allowedRoles, requiredApprovals)
— this is the screen that most directly proves the engine is
YAML-driven: `privileged-access:v1` and `privileged-access:v2` both
render correctly from the exact same code, differing only in what data
the registry loaded.

## 6. What does NOT change

- `banking-service/static/ui.html` — untouched, stays the create-and-
  watch tool.
- Every existing `approval-engine` endpoint's request/response shape —
  unchanged; this only adds two new GETs and one new collection GET.
- No new database columns, no migration.

## 7. Tests

- `ApprovalRequestRepositoryTest`: `findAllByOrderByCreatedAtDesc()`
  returns rows newest-first.
- `ApprovalControllerTest`: `GET /approvals` returns all created
  requests; `?status=pending`/`?status=completed` filter correctly
  against a mix of a still-`PENDING_APPROVAL` request and an `APPROVED`
  one.
- New `WorkflowControllerTest`: `GET /workflows` includes every fixture
  workflow (all three transfer workflows, both `privileged-access`
  versions); `GET /workflows/privileged-access/2` returns
  `SECURITY_REVIEW`'s `approve` transition with `requiredApprovals: 2`
  (distinguishing it from `/privileged-access/1`'s `1`); unknown
  `workflowId`/`version` returns 404.
- UI: manual smoke test only (per this repo's existing convention for
  `ui.html` — no browser test harness exists in this codebase and
  introducing one is out of scope).
