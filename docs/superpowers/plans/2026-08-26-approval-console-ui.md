# Approval Console UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-mostly "Approval Console" to `approval-engine`: a requests list, a request-detail screen (pipeline + timeline + actions), and a workflow-definitions browser — served directly from `approval-engine` itself, calling its own REST API.

**Architecture:** Two new read endpoints (`GET /approvals` list, `GET /workflows` + `GET /workflows/{id}/{version}`) built entirely from data that already exists (`ApprovalRequest`, `policy_snapshot.workflow`, `audit_log`, `approval_decision`, the already-loaded `WorkflowRegistry`) — no schema change. A plain-JS, no-build static site in `approval-engine/src/main/resources/static/`, served by Spring Boot's default static-resource handling (already proven by `banking-service`'s `ui.html` using the identical mechanism).

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, AssertJ, MockMvc, Testcontainers (backend); vanilla JS/HTML/CSS, no framework, no build step (frontend).

**Spec:** `docs/superpowers/specs/2026-08-26-approval-console-ui-design.md`

## Global Constraints

- Purely additive: no changes to any existing endpoint's request/response shape, no new database columns, no migration (spec, throughout).
- No pagination on `GET /approvals` — demo-scale dataset, YAGNI (spec §3).
- No build step, no frontend framework — plain `<script>` tags, matching `ui.html`'s existing style (spec §5).
- The UI's actor dropdown is a display convenience only, never an auth mechanism — the command endpoints independently re-validate role/state/guard regardless of what's shown (spec §1, §5).

---

## Task 1: `GET /approvals` list endpoint

**Files:**
- Modify: `approval-engine/src/main/java/com/visionbank/approval/repository/ApprovalRequestRepository.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/ApprovalRequestSummaryDto.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/ApprovalController.java`
- Modify/Test: `approval-engine/src/test/java/com/visionbank/approval/repository/ApprovalRequestRepositoryTest.java`
- Modify/Test: `approval-engine/src/test/java/com/visionbank/approval/web/ApprovalControllerTest.java`

**Interfaces:**
- Consumes: `ApprovalRequest.getPolicySnapshot().workflow()` (existing, from the prior plan), `ApprovalDecisionRepository.countByRequestIdAndDecisionAndState` (existing).
- Produces: `ApprovalRequestSummaryDto(String requestId, String workflowId, int workflowVersion, String currentState, String currentStageLabel, boolean terminal, Integer requiredApprovals, Integer currentApprovals, Instant createdAt)`, `GET /approvals?status=pending|completed|all` — Task 3 (the UI) consumes this shape directly.

- [ ] **Step 1: Add `findAllByOrderByCreatedAtDesc()` to `ApprovalRequestRepository.java`**

Add this one method to the existing interface (do not touch `findByRequestId`, `guardedTransition`, `findByStateInAndExpiresAtBefore`, or `findByRequestIdForUpdate`):
```java
    List<ApprovalRequest> findAllByOrderByCreatedAtDesc();
```

- [ ] **Step 2: Write the failing test for the repository query**

Add to `ApprovalRequestRepositoryTest.java` (same file, same `TEST_WORKFLOW` fixture and `newRequest(String id)` helper already there — do not duplicate them):
```java
    @Test
    void findAllByOrderByCreatedAtDescReturnsNewestFirst() {
        ApprovalRequest older = newRequest("order-old");
        older.setCreatedAt(Instant.now().minusSeconds(60));
        repository.saveAndFlush(older);

        ApprovalRequest newer = newRequest("order-new");
        newer.setCreatedAt(Instant.now());
        repository.saveAndFlush(newer);

        List<ApprovalRequest> all = repository.findAllByOrderByCreatedAtDesc();

        int oldIdx = indexOfRequestId(all, "order-old");
        int newIdx = indexOfRequestId(all, "order-new");
        assertThat(newIdx).isLessThan(oldIdx);
    }

    private int indexOfRequestId(List<ApprovalRequest> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getRequestId().equals(id)) return i;
        }
        throw new AssertionError("requestId not found: " + id);
    }
```
(Other tests in this `@DataJpaTest` class may have left rows from earlier tests in the same run — the index-comparison approach above is robust to that; don't assert an exact list size or exact position.)

- [ ] **Step 2b: Run to verify it fails to compile** (method doesn't exist yet if you did Step 2 before Step 1 — if you followed the order above it should already pass once Step 1 is done; run anyway to confirm)

```bash
cd approval-engine && ./gradlew test --tests "com.visionbank.approval.repository.ApprovalRequestRepositoryTest"
```
Expected: PASS (3 existing tests + this new one, 4/4).

- [ ] **Step 3: Create `ApprovalRequestSummaryDto.java`**

```java
package com.visionbank.approval.web.dto;

import java.time.Instant;

public record ApprovalRequestSummaryDto(
        String requestId, String workflowId, int workflowVersion,
        String currentState, String currentStageLabel, boolean terminal,
        Integer requiredApprovals, Integer currentApprovals,
        Instant createdAt) {}
```

- [ ] **Step 4: Add `list()` to `ApprovalController.java`**

Add this method and its private helper `toSummary` alongside the existing methods (do not modify `create`/`approve`/`reject`/`cancel`/`get`/`audit`/`workflowView`/`buildStageView`/`hasEverReached`/`isSuccessTerminal` — this is additive only):
```java
    @GetMapping
    public List<ApprovalRequestSummaryDto> list(@RequestParam(required = false, defaultValue = "all") String status) {
        return requests.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .filter(s -> switch (status) {
                    case "pending" -> !s.terminal();
                    case "completed" -> s.terminal();
                    default -> true;
                })
                .toList();
    }

    private ApprovalRequestSummaryDto toSummary(ApprovalRequest request) {
        WorkflowDefinition workflow = request.getPolicySnapshot().workflow();
        String currentState = request.getState();
        String label = workflow.states().stream()
                .filter(s -> s.id().equals(currentState))
                .findFirst()
                .map(WorkflowDefinition.StateDef::label)
                .orElse(currentState);
        boolean terminal = workflow.isTerminal(currentState);

        Transition approveFromHere = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("approve") && t.requiredApprovals() != null)
                .findFirst()
                .orElse(null);
        Integer requiredApprovals = approveFromHere == null ? null : approveFromHere.requiredApprovals();
        Integer currentApprovals = approveFromHere == null ? null
                : (int) decisions.countByRequestIdAndDecisionAndState(
                        request.getRequestId(), ApprovalDecision.DecisionType.APPROVE, currentState);

        return new ApprovalRequestSummaryDto(request.getRequestId(), workflow.name(), workflow.version(),
                currentState, label, terminal, requiredApprovals, currentApprovals, request.getCreatedAt());
    }
```
`toSummary`'s `approveFromHere` lookup is deliberately the same pattern `buildStageView` already uses for a single state, not a shared extraction — the two apply it in different contexts (`buildStageView` loops over every state for one request, `toSummary` runs once per request over many requests) and forcing a shared helper would need a signature change to `buildStageView` for no real benefit. `getPolicySnapshot()` needs `import java.time.Instant` if not already present — check before adding (this file may already import it transitively; add explicitly if compilation fails without it, `ApprovalRequestSummaryDto` itself does, but the controller doesn't necessarily need to import `Instant` since it never names the type directly — only add if the compiler actually requires it).

- [ ] **Step 5: Add controller tests**

Add to `ApprovalControllerTest.java` (reuse the existing `createDto(String, String)` helper already in the file):
```java
    @Test
    void listReturnsAllCreatedRequestsNewestFirst() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("list-1", "transfer-single-checker")));
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("list-2", "transfer-auto-release")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId=='list-1')].currentState", is(List.of("PENDING_APPROVAL"))))
                .andExpect(jsonPath("$[?(@.requestId=='list-2')].currentState", is(List.of("APPROVED"))))
                .andExpect(jsonPath("$[?(@.requestId=='list-2')].terminal", is(List.of(true))))
                .andExpect(jsonPath("$[?(@.requestId=='list-1')].terminal", is(List.of(false))));
    }

    @Test
    void statusFilterSeparatesPendingFromCompleted() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("filter-pending-1", "transfer-single-checker")));
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("filter-done-1", "transfer-auto-release")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals?status=pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId=='filter-pending-1')]").exists())
                .andExpect(jsonPath("$[?(@.requestId=='filter-done-1')]").doesNotExist());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals?status=completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId=='filter-done-1')]").exists())
                .andExpect(jsonPath("$[?(@.requestId=='filter-pending-1')]").doesNotExist());
    }
```

- [ ] **Step 6: Run the full `approval-engine` test suite and commit**

```bash
cd approval-engine && ./gradlew test
git add approval-engine/
git commit -m "$(cat <<'EOF'
feat: add GET /approvals list endpoint with pending/completed filter

Purely additive -- backs the new Approval Console's Requests screen.
No changes to any existing endpoint. Reuses the same approve-transition
lookup pattern workflow-view already uses, applied to one state per
request instead of every state.
EOF
)"
```

---

## Task 2: `GET /workflows`, `GET /workflows/{workflowId}/{version}`

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/WorkflowController.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/WorkflowSummaryDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/WorkflowDefinitionDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/WorkflowStateDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/WorkflowTransitionDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/WorkflowNotFoundException.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/ApiExceptionHandler.java`
- Create: `approval-engine/src/test/java/com/visionbank/approval/web/WorkflowControllerTest.java`

**Interfaces:**
- Consumes: `WorkflowRegistry.all()`/`.get(workflowId, version)` (existing, from the prior plan).
- Produces: `GET /workflows` → `List<WorkflowSummaryDto>`; `GET /workflows/{id}/{version}` → `WorkflowDefinitionDto` (404 via `WorkflowNotFoundException` on an unknown pair) — Task 3 (the UI) consumes both directly.

- [ ] **Step 1: Create the four DTOs**

```java
package com.visionbank.approval.web.dto;

public record WorkflowSummaryDto(String workflowId, int version, int stateCount) {}
```

```java
package com.visionbank.approval.web.dto;

public record WorkflowStateDto(String id, String label) {}
```

```java
package com.visionbank.approval.web.dto;

import java.util.List;

public record WorkflowTransitionDto(String name, String from, String to, List<String> guards,
                                     List<String> allowedRoles, Integer requiredApprovals) {}
```

```java
package com.visionbank.approval.web.dto;

import java.util.List;

public record WorkflowDefinitionDto(String workflowId, int version, String initialState,
                                     List<String> terminalStates, List<WorkflowStateDto> states,
                                     List<WorkflowTransitionDto> transitions) {}
```

- [ ] **Step 2: Create `WorkflowNotFoundException.java`** (mirrors `ApprovalRequestNotFoundException`'s existing pattern exactly)

```java
package com.visionbank.approval.service;

public class WorkflowNotFoundException extends RuntimeException {
    public WorkflowNotFoundException(String workflowId, int version) {
        super("No workflow definition for " + workflowId + ":" + version);
    }
}
```

- [ ] **Step 3: Add the exception handler to `ApiExceptionHandler.java`**

Add this method alongside the existing six handlers (don't modify any of them):
```java
    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handle(WorkflowNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponseDto("WORKFLOW_NOT_FOUND", null, null, null));
    }
```

- [ ] **Step 4: Create `WorkflowController.java`**

```java
package com.visionbank.approval.web;

import com.visionbank.approval.service.WorkflowNotFoundException;
import com.visionbank.approval.web.dto.*;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    private final WorkflowRegistry registry;

    public WorkflowController(WorkflowRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<WorkflowSummaryDto> list() {
        return registry.all().stream()
                .map(d -> new WorkflowSummaryDto(d.name(), d.version(), d.states().size()))
                .toList();
    }

    @GetMapping("/{workflowId}/{version}")
    public WorkflowDefinitionDto get(@PathVariable String workflowId, @PathVariable int version) {
        WorkflowDefinition def;
        try {
            def = registry.get(workflowId, version);
        } catch (IllegalStateException e) {
            throw new WorkflowNotFoundException(workflowId, version);
        }
        return toDto(def);
    }

    private WorkflowDefinitionDto toDto(WorkflowDefinition def) {
        List<WorkflowStateDto> states = def.states().stream()
                .map(s -> new WorkflowStateDto(s.id(), s.label()))
                .toList();
        List<WorkflowTransitionDto> transitions = def.transitions().stream()
                .map(this::toDto)
                .toList();
        return new WorkflowDefinitionDto(def.name(), def.version(), def.initialState(),
                new ArrayList<>(def.terminalStates()), states, transitions);
    }

    private WorkflowTransitionDto toDto(Transition t) {
        return new WorkflowTransitionDto(t.name(), t.from(), t.to(), t.guards(), t.allowedRoles(), t.requiredApprovals());
    }
}
```

- [ ] **Step 5: Create `WorkflowControllerTest.java`**

```java
package com.visionbank.approval.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;

    @Test
    void listIncludesEveryFixtureWorkflow() throws Exception {
        mockMvc.perform(get("/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.workflowId=='transfer-auto-release')]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='transfer-single-checker')]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='transfer-high-value')]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='privileged-access' && @.version==1)]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='privileged-access' && @.version==2)]").exists());
    }

    @Test
    void detailDistinguishesPrivilegedAccessVersionsByRequiredApprovals() throws Exception {
        mockMvc.perform(get("/workflows/privileged-access/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitions[?(@.name=='approve' && @.from=='SECURITY_REVIEW')].requiredApprovals",
                        is(List.of(1))));

        mockMvc.perform(get("/workflows/privileged-access/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitions[?(@.name=='approve' && @.from=='SECURITY_REVIEW')].requiredApprovals",
                        is(List.of(2))));
    }

    @Test
    void unknownWorkflowReturns404() throws Exception {
        mockMvc.perform(get("/workflows/does-not-exist/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownVersionOfKnownWorkflowReturns404() throws Exception {
        mockMvc.perform(get("/workflows/privileged-access/99"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 6: Run the full `approval-engine` test suite and commit**

```bash
cd approval-engine && ./gradlew test
git add approval-engine/
git commit -m "$(cat <<'EOF'
feat: add GET /workflows and GET /workflows/{id}/{version}

Read-only exposure of the already-versioned WorkflowRegistry over HTTP
for the first time -- backs the Approval Console's Workflow Definitions
screen. Purely additive.
EOF
)"
```

---

## Task 3: Console UI (`console.html`/`console.css`/`console.js`/`console-api.js`)

**Files:**
- Create: `approval-engine/src/main/resources/static/console.html`
- Create: `approval-engine/src/main/resources/static/console.css`
- Create: `approval-engine/src/main/resources/static/console.js`
- Create: `approval-engine/src/main/resources/static/console-api.js`

**Interfaces:**
- Consumes: `GET /approvals` (Task 1), `GET /workflows`, `GET /workflows/{id}/{version}` (Task 2), `GET /approvals/{id}/workflow-view`, `GET /approvals/{id}/audit`, `POST /approvals/{id}/{approve|reject|cancel}` (all pre-existing).
- Produces: nothing consumed by a later task — this is the last task.

- [ ] **Step 1: Create `console-api.js`** — thin fetch wrappers, one per endpoint, no logic beyond parsing JSON and surfacing HTTP errors

```javascript
const API = (() => {
  async function get(path) {
    const res = await fetch(path);
    if (!res.ok) throw new Error(path + ' -> ' + res.status);
    return res.json();
  }
  async function post(path, body) {
    const res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error((err.code || res.status) + ': ' + path);
    }
    return res.json();
  }

  return {
    listApprovals: (status) => get('/approvals?status=' + (status || 'all')),
    getWorkflowView: (id) => get('/approvals/' + id + '/workflow-view'),
    getAudit: (id) => get('/approvals/' + id + '/audit'),
    decide: (id, action, actorId, actorRole) =>
      post('/approvals/' + id + '/' + action, { actorId, actorRole }),
    listWorkflows: () => get('/workflows'),
    getWorkflow: (id, version) => get('/workflows/' + id + '/' + version)
  };
})();
```

- [ ] **Step 2: Create `console.css`** — minimal, reuses `ui.html`'s existing visual language (same color choices for pending/active/success/failed, same font stack) so the two tools feel like one system

```css
* { box-sizing: border-box; }
body { font-family: -apple-system, Segoe UI, sans-serif; margin: 0; background: #f4f5f7; color: #222; }
header { background: #1f2937; color: #fff; padding: 12px 20px; display: flex; justify-content: space-between; align-items: center; }
header h1 { margin: 0; font-size: 16px; font-weight: 600; }
header select { padding: 4px 8px; font-size: 13px; }

#shell { display: flex; height: calc(100vh - 49px); }
#nav { flex: 0 0 160px; background: #fff; border-right: 1px solid #ddd; padding: 16px 0; }
#nav a { display: block; padding: 8px 20px; color: #333; text-decoration: none; font-size: 13px; }
#nav a:hover, #nav a.active { background: #eff6ff; color: #2563eb; font-weight: 600; }
#content { flex: 1; padding: 20px; overflow-y: auto; }

table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #eee; font-size: 13px; }
th { color: #666; font-weight: 600; text-transform: uppercase; font-size: 11px; }
tr.row-link { cursor: pointer; }
tr.row-link:hover { background: #f8fafc; }

.tabs { margin-bottom: 12px; }
.tabs a { padding: 6px 12px; font-size: 13px; text-decoration: none; color: #555; border: 1px solid #ddd; border-radius: 3px; margin-right: 6px; display: inline-block; }
.tabs a.active { background: #2563eb; color: #fff; border-color: #2563eb; }

.pipeline { display: flex; align-items: center; gap: 0; margin: 16px 0; flex-wrap: wrap; }
.pipe-stage { border: 1px solid #d1d5db; border-radius: 4px; padding: 8px 12px; background: #fff; font-size: 12px; text-align: center; min-width: 120px; }
.pipe-stage.pending { opacity: 0.55; }
.pipe-stage.active { border-color: #2563eb; background: #eff6ff; }
.pipe-stage.success { border-color: #16a34a; background: #f0fdf4; }
.pipe-stage.failed { border-color: #dc2626; background: #fef2f2; }
.pipe-arrow { padding: 0 6px; color: #999; }

.actions button { padding: 7px 14px; font-size: 13px; border: none; border-radius: 3px; cursor: pointer; color: #fff; margin-right: 8px; }
#btn-approve { background: #16a34a; }
#btn-reject { background: #dc2626; }
#btn-cancel { background: #6b7280; }

.timeline-entry { border-left: 2px solid #d1d5db; padding: 6px 0 6px 12px; margin-bottom: 4px; font-size: 12px; }
.timeline-entry .action { font-weight: 600; }
.timeline-entry .meta { color: #999; font-size: 11px; }

.transition-row { font-size: 12px; padding: 4px 0; border-bottom: 1px solid #f0f0f0; }
.state-chain { font-size: 13px; margin: 12px 0; }
```

- [ ] **Step 3: Create `console.js`** — hash router + the three pages + the actor dropdown + timeline rendering

```javascript
const ROLES = ['MAKER', 'TRANSFER_CHECKER', 'RISK_CHECKER', 'TRANSFER_MANAGER', 'COMPLIANCE_OFFICER', 'SECURITY', 'MANAGER', 'COMPLIANCE'];

function currentRole() {
  return localStorage.getItem('console-actor-role') || ROLES[0];
}
function setRole(role) {
  localStorage.setItem('console-actor-role', role);
}

function el(html) {
  const div = document.createElement('div');
  div.innerHTML = html.trim();
  return div.firstChild;
}

function renderNav(active) {
  const nav = document.getElementById('nav');
  nav.innerHTML = '';
  [['#requests', 'Requests'], ['#workflows', 'Workflows']].forEach(([hash, label]) => {
    const a = document.createElement('a');
    a.href = hash;
    a.textContent = label;
    if (active === label.toLowerCase()) a.className = 'active';
    nav.appendChild(a);
  });
}

function renderRoleSelect() {
  const select = document.getElementById('actor-role');
  select.innerHTML = ROLES.map(r => '<option value="' + r + '">' + r + '</option>').join('');
  select.value = currentRole();
  select.onchange = () => { setRole(select.value); route(); };
}

async function requestsPage(status) {
  status = status || 'all';
  renderNav('requests');
  const content = document.getElementById('content');
  content.innerHTML = '<div class="tabs">' +
    ['all', 'pending', 'completed'].map(s =>
      '<a href="#requests/' + s + '" class="' + (s === status ? 'active' : '') + '">' + s + '</a>').join('') +
    '</div><table><thead><tr><th>Request</th><th>Workflow</th><th>Current Stage</th><th>Approval</th><th>Status</th></tr></thead><tbody id="requests-body"></tbody></table>';

  const rows = await API.listApprovals(status);
  const body = document.getElementById('requests-body');
  body.innerHTML = '';
  rows.forEach(r => {
    const tr = el('<tr class="row-link"><td>' + r.requestId + '</td><td>' + r.workflowId + ':' + r.workflowVersion + '</td><td>' +
      r.currentStageLabel + '</td><td>' + (r.requiredApprovals != null ? r.currentApprovals + ' / ' + r.requiredApprovals : '—') +
      '</td><td>' + (r.terminal ? r.currentState : 'PENDING') + '</td></tr>');
    tr.onclick = () => { location.hash = '#requests/detail/' + r.requestId; };
    body.appendChild(tr);
  });
}

function pipelineHtml(view) {
  return '<div class="pipeline">' + view.stages.map((s, i) => {
    const cls = s.status === 'IN_PROGRESS' ? 'active' : s.status === 'COMPLETED' ? 'success' : s.status === 'FAILED' ? 'failed' : 'pending';
    const progress = s.requiredApprovals != null ? '<div>' + s.completedApprovals + ' / ' + s.requiredApprovals + '</div>' : '';
    const arrow = i < view.stages.length - 1 ? '<span class="pipe-arrow">&rarr;</span>' : '';
    return '<div class="pipe-stage ' + cls + '"><div>' + s.label + '</div>' + progress + '</div>' + arrow;
  }).join('') + '</div>';
}

function updateActionButtons(view) {
  const role = currentRole();
  const actions = view.availableActions || [];
  ['approve', 'reject', 'cancel'].forEach(name => {
    const btn = document.getElementById('btn-' + name);
    if (!btn) return;
    const action = actions.find(a => a.name === name);
    const allowed = !!action && (action.allowedRoles.length === 0 || action.allowedRoles.includes(role));
    btn.style.display = allowed ? '' : 'none';
  });
}

async function requestDetailPage(id) {
  renderNav('requests');
  const content = document.getElementById('content');
  content.innerHTML = '<div id="detail-body">Loading…</div>';

  async function refresh() {
    const view = await API.getWorkflowView(id);
    const audit = await API.getAudit(id);
    const detail = document.getElementById('detail-body');
    detail.innerHTML =
      '<h2>' + id + ' <span style="font-weight:400;color:#666;font-size:14px">' + view.workflowId + ':' + view.workflowVersion + '</span></h2>' +
      pipelineHtml(view) +
      '<div class="actions">' +
      '<button id="btn-approve">Approve</button>' +
      '<button id="btn-reject">Reject</button>' +
      '<button id="btn-cancel">Cancel</button>' +
      '</div>' +
      '<h3>Workflow History</h3>' +
      '<div id="timeline"></div>';

    const timeline = document.getElementById('timeline');
    audit.slice().reverse().forEach(a => {
      timeline.appendChild(el('<div class="timeline-entry"><div class="action">' + a.action + ': ' +
        a.previousState + ' &rarr; ' + a.newState + '</div><div class="meta">' + a.createdAt +
        (a.actorId ? ' · ' + a.actorId + (a.actorRole ? ' (' + a.actorRole + ')' : '') : '') + '</div></div>'));
    });

    updateActionButtons(view);
    ['approve', 'reject', 'cancel'].forEach(name => {
      const btn = document.getElementById('btn-' + name);
      if (btn) btn.onclick = async () => {
        const actorId = prompt('actorId:');
        if (!actorId) return;
        try {
          await API.decide(id, name, actorId, currentRole());
          await refresh();
        } catch (e) {
          alert(e.message);
        }
      };
    });
  }

  await refresh();
}

async function workflowsPage() {
  renderNav('workflows');
  const content = document.getElementById('content');
  content.innerHTML = '<table><thead><tr><th>Workflow</th><th>Version</th><th>States</th></tr></thead><tbody id="workflows-body"></tbody></table>';
  const rows = await API.listWorkflows();
  const body = document.getElementById('workflows-body');
  rows.forEach(w => {
    const tr = el('<tr class="row-link"><td>' + w.workflowId + '</td><td>' + w.version + '</td><td>' + w.stateCount + '</td></tr>');
    tr.onclick = () => { location.hash = '#workflows/detail/' + w.workflowId + '/' + w.version; };
    body.appendChild(tr);
  });
}

async function workflowDetailPage(id, version) {
  renderNav('workflows');
  const content = document.getElementById('content');
  const def = await API.getWorkflow(id, version);
  content.innerHTML =
    '<h2>' + def.workflowId + ':' + def.version + '</h2>' +
    '<div class="state-chain">' + def.states.map(s => s.id).join(' &rarr; ') + '</div>' +
    def.states.map(s => {
      const from = def.transitions.filter(t => t.from === s.id);
      if (from.length === 0) return '';
      return '<h4>' + s.id + '</h4>' + from.map(t =>
        '<div class="transition-row">' + t.name + ' &rarr; ' + t.to +
        (t.allowedRoles.length ? ' · allowed: ' + t.allowedRoles.join(', ') : '') +
        (t.requiredApprovals != null ? ' · approvals: ' + t.requiredApprovals : '') +
        (t.guards.length ? ' · guards: ' + t.guards.join(', ') : '') + '</div>').join('');
    }).join('');
}

function route() {
  const hash = location.hash.replace(/^#/, '');
  const parts = hash.split('/');
  if (parts[0] === 'requests' && parts[1] === 'detail' && parts[2]) {
    requestDetailPage(parts[2]);
  } else if (parts[0] === 'requests') {
    requestsPage(parts[1]);
  } else if (parts[0] === 'workflows' && parts[1] === 'detail' && parts[2] && parts[3]) {
    workflowDetailPage(parts[2], parts[3]);
  } else if (parts[0] === 'workflows') {
    workflowsPage();
  } else {
    location.hash = '#requests';
  }
}

window.addEventListener('hashchange', route);
window.addEventListener('DOMContentLoaded', () => {
  renderRoleSelect();
  route();
});
```

- [ ] **Step 4: Create `console.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Approval Engine Console</title>
<link rel="stylesheet" href="console.css">
</head>
<body>
<header>
  <h1>Approval Engine Console</h1>
  <div>Actor: <select id="actor-role"></select></div>
</header>
<div id="shell">
  <div id="nav"></div>
  <div id="content"></div>
</div>
<script src="console-api.js"></script>
<script src="console.js"></script>
</body>
</html>
```

- [ ] **Step 5: Run the `approval-engine` test suite** (no backend changed in this task, but confirms nothing broke)

```bash
cd approval-engine && ./gradlew test
```

- [ ] **Step 6: Manual smoke test** — `docker compose up -d postgres`, start `approval-engine` (`cd approval-engine && ./gradlew bootRun`) and `banking-service`, submit a couple of transfers of different sizes via `banking-service`'s existing `ui.html` (or `curl`) so there's data to look at, then open `http://localhost:8081/console.html`:
  - Requests screen lists them, tabs filter correctly.
  - Clicking one opens the detail screen: pipeline renders, timeline shows the audit trail, action buttons show/hide as the actor dropdown changes.
  - Approve/Reject/Cancel actually work and the screen refreshes.
  - Workflows screen lists all five loaded definitions (three transfer workflows, `privileged-access` v1 and v2); clicking `privileged-access` v1 vs v2 shows the different `requiredApprovals` on `SECURITY_REVIEW`'s approve transition.

- [ ] **Step 7: Commit**

```bash
git add approval-engine/src/main/resources/static/
git commit -m "$(cat <<'EOF'
feat: add Approval Console UI (requests, request detail, workflows)

Plain JS/HTML/CSS, no build step, served directly from approval-engine.
Requests screen (list + pending/completed filter), Request Detail
(horizontal pipeline + workflow history timeline + role-gated action
buttons), Workflow Definitions (states/transitions per version,
including privileged-access v1 vs v2 side by side). All three screens
are read projections of existing backend state -- no client-side state
machine.
EOF
)"
```

---

## Self-Review

**Spec coverage:** §2 (hosting on approval-engine) → Task 3 (files live under `approval-engine/src/main/resources/static/`). §3 (`GET /approvals` list) → Task 1. §4 (`GET /workflows`) → Task 2. §5 (UI structure, all three screens + actor dropdown) → Task 3. The Workflow History addition → Task 3 Step 3 (`requestDetailPage`'s timeline rendering, backed by the pre-existing `GET /approvals/{id}/audit`, no new endpoint). §6 (nothing else changes) → verified by Task 1/2's "additive only" file lists. §7 (tests) → Task 1 Steps 2/5, Task 2 Step 5, Task 3 Step 6 (manual, per spec's own acknowledgment that no browser test harness exists in this repo).

**Placeholder scan:** none — every step has complete file content or a real command.

**Type consistency:** `ApprovalRequestSummaryDto`, `WorkflowSummaryDto`/`WorkflowDefinitionDto`/`WorkflowStateDto`/`WorkflowTransitionDto` are defined once (Task 1 Step 3, Task 2 Step 1) and consumed with matching field names in both the controllers (Task 1 Step 4, Task 2 Step 4) and the frontend (Task 3 Step 3, which reads `r.workflowId`/`r.currentStageLabel`/etc. and `w.workflowId`/`w.stateCount`/etc. matching the DTOs exactly, and `def.states`/`def.transitions` with `t.allowedRoles`/`t.requiredApprovals`/`t.guards` matching `WorkflowTransitionDto`'s fields).
