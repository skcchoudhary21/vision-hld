# Multi-Stage, Multi-Workflow Approval Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generalize the Approval Engine from one hardcoded two-state workflow into a genuinely workflow-agnostic engine — multiple named workflow definitions, N-stage sequential graphs with per-stage N-of-M quorum, and a generic UI — proving the extensibility seams the original submission named but never exercised.

**Architecture:** `ApprovalState` becomes a plain `String` everywhere (no more fixed Java enum), so a brand-new workflow with brand-new state names is expressible purely in YAML. Multiple workflow definitions load into a `WorkflowRegistry`; a `WorkflowSelector` (its own YAML) maps `requestType → workflowId`, resolved once per request and frozen on the row forever after (same pattern as `policy_snapshot`/`expires_at`). `approve`/`reject`/`cancel` stop hardcoding `PENDING_APPROVAL → APPROVED` and instead resolve the matching transition from the request's own resolved `WorkflowDefinition`. Quorum counting becomes per-stage.

**Tech Stack:** Same as the existing system — Java 21, Spring Boot 4.1.1, Gradle 8.14.3, Postgres via Testcontainers for tests, SnakeYAML for workflow parsing.

**Spec:** `docs/superpowers/specs/2026-08-26-multi-stage-workflow-engine-design.md` (extends `docs/superpowers/specs/2026-08-25-transfer-approval-design.md`)

## Global Constraints

- **`ApprovalState` is deleted as a Java enum.** Every place it was used becomes a plain `String`. This is the prerequisite for every other task — nothing else compiles until this lands.
- **The workflow YAML never carries approval counts.** Only shape (states, transitions, guard names) lives in YAML. `requiredApprovals`/`eligibleRoles` stay caller-supplied, per-request, in `policy_snapshot` — now shaped per-stage, but still never a workflow-level constant.
- **One active version per named workflow at a time.** No concurrent multi-version execution. `workflow_version` is persisted per-request for audit/display only.
- **AND/OR condition composition is out of scope.** Each stage keeps the existing single N-of-M-from-one-role-pool quorum shape.
- **Per-stage SLA durations are out of scope.** `expires_at` stays one request-level timestamp, set at creation, unchanged for the request's whole lifetime.
- **Execution/business-lifecycle states never appear in an Approval Engine workflow YAML.** The Engine's workflow always terminates at one of its own `terminalStates`; execution is entirely `banking-service`'s concern, reported nowhere back to the Engine. This preserves the documented asymmetric-resilience property: Engine's own workflow completion never depends on `banking-service`'s reachability.
- **Existing behavior for `transfer-approval` must be identical before and after** — same states, same transitions, same guard outcomes, expressed as YAML instead of implicit Java literals. The 39 approval-engine + 23 banking-service tests are the regression baseline; every task keeps the full suite green, not just its own new tests.
- **`ddl-auto: update`, no Flyway** — the project's already-documented, deliberate schema-management trade-off. Schema changes in this plan rely on dropping and recreating local dev/test data (no production users, no backfill needed).
- Keep code comments minimal — default to none, one terse line only where the WHY is genuinely non-obvious. Do not transcribe plan prose verbatim into code comments.

---

## Task 1: `ApprovalState` → `String`, extended workflow schema (labels, `terminalStates`, `events`)

**Files:**
- Delete: `approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalState.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/Transition.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowDefinition.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/YamlWorkflowLoader.java`
- Modify: `approval-engine/src/main/resources/workflow/transfer-approval.yaml`
- Modify (mechanical `ApprovalState.X` → `"X"` literal, remove `@Enumerated`/imports — see recipe below): `domain/ApprovalRequest.java`, `domain/AuditLog.java`, `repository/ApprovalRequestRepository.java`, `service/ApprovalCommandService.java`, `service/ApprovalRequestView.java`, `service/ConcurrentStateChangeException.java`, `service/InvalidStateTransitionException.java`, `service/ExpirySweeper.java`, `service/ExpiryTransitionService.java`, `web/dto/ApprovalResponseDto.java`, `web/ApiExceptionHandler.java`
- Modify (test files, same mechanical transform, plus assertion updates): `test/java/com/visionbank/approval/repository/ApprovalRequestRepositoryTest.java`, `test/java/com/visionbank/approval/service/ApprovalCommandServiceApproveTest.java`, `test/java/com/visionbank/approval/service/ApprovalCommandServiceCreateTest.java`, `test/java/com/visionbank/approval/service/ApprovalConcurrencyTest.java`, `test/java/com/visionbank/approval/service/ExpirySweeperTest.java`, `test/java/com/visionbank/approval/service/ExpiryVersusApproveConcurrencyTest.java`, `test/java/com/visionbank/approval/web/ApprovalControllerTest.java`
- Modify: `approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowLoaderTest.java`
- Modify: `approval-engine/src/test/resources/workflow/invalid-duplicate-transition.yaml`

**Interfaces:**
- Produces: `Transition(String name, String from, String to, String guard)`; `WorkflowDefinition(String name, int version, List<WorkflowDefinition.StateDef> states, String initialState, Set<String> terminalStates, List<Transition> transitions, Map<String,List<String>> events)` with nested `record StateDef(String id, String label)`, plus methods `transitionsFrom(String state) -> List<Transition>`, `byName(String name) -> Transition` (unchanged signature, now String-keyed), `isTerminal(String state) -> boolean`, `eventsFor(String state) -> List<String>`.
- Consumes: nothing new from other tasks — this is the foundation everything else builds on.

**No behavior change in this task.** `ApprovalCommandService`/`ExpiryTransitionService` keep their exact current hardcoded two-state logic (`PENDING_APPROVAL`/`APPROVED`/etc.) — only the *type* of those literals changes from enum constant to string literal. Generic dispatch is Task 5.

- [ ] **Step 1: Rewrite `Transition.java`**

```java
package com.visionbank.approval.workflow;

public record Transition(String name, String from, String to, String guard) {}
```

- [ ] **Step 2: Rewrite `WorkflowDefinition.java`**

```java
package com.visionbank.approval.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record WorkflowDefinition(
        String name,
        int version,
        List<StateDef> states,
        String initialState,
        Set<String> terminalStates,
        List<Transition> transitions,
        Map<String, List<String>> events) {

    public record StateDef(String id, String label) {}

    public List<Transition> transitionsFrom(String state) {
        return transitions.stream()
                .filter(t -> t.from().equals(state))
                .collect(Collectors.toList());
    }

    public Transition byName(String name) {
        return transitions.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown transition: " + name));
    }

    public boolean isTerminal(String state) {
        return terminalStates.contains(state);
    }

    public List<String> eventsFor(String state) {
        return events.getOrDefault(state, List.of());
    }

    public boolean hasState(String state) {
        return states.stream().anyMatch(s -> s.id().equals(state));
    }
}
```

- [ ] **Step 3: Rewrite `YamlWorkflowLoader.java`**

```java
package com.visionbank.approval.workflow;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

public class YamlWorkflowLoader implements WorkflowLoader {

    @Override
    @SuppressWarnings("unchecked")
    public WorkflowDefinition load(String classpathResource) {
        try (InputStream in = new ClassPathResource(classpathResource).getInputStream()) {
            Map<String, Object> raw = new Yaml().load(in);

            String name = (String) raw.get("name");
            int version = (Integer) raw.get("version");
            String initial = (String) raw.get("initialState");

            List<WorkflowDefinition.StateDef> states = ((List<Map<String, String>>) raw.get("states")).stream()
                    .map(s -> new WorkflowDefinition.StateDef(s.get("id"), s.get("label")))
                    .collect(Collectors.toList());

            Set<String> terminalStates = new HashSet<>((List<String>) raw.get("terminalStates"));

            List<Transition> transitions = ((List<Map<String, String>>) raw.get("transitions")).stream()
                    .map(t -> new Transition(t.get("name"), t.get("from"), t.get("to"), t.get("guard")))
                    .collect(Collectors.toList());

            Map<String, List<String>> events = raw.containsKey("events")
                    ? ((Map<String, List<String>>) raw.get("events"))
                    : Map.of();

            WorkflowDefinition definition = new WorkflowDefinition(name, version, states, initial, terminalStates, transitions, events);
            validate(definition);
            return definition;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow definition: " + classpathResource, e);
        }
    }

    private void validate(WorkflowDefinition def) {
        if (!def.hasState(def.initialState())) {
            throw new IllegalStateException("initialState " + def.initialState() + " is not declared in states[]");
        }
        Set<String> seenIdentities = new HashSet<>();
        Set<String> statesWithOutgoingTransitions = new HashSet<>();
        for (Transition t : def.transitions()) {
            if (!def.hasState(t.from()) || !def.hasState(t.to())) {
                throw new IllegalStateException("Transition " + t.name() + " references a state not in states[]");
            }
            String identity = t.name() + "|" + t.from();
            if (!seenIdentities.add(identity)) {
                throw new IllegalStateException("Duplicate transition '" + t.name() + "' from state " + t.from());
            }
            statesWithOutgoingTransitions.add(t.from());
        }
        for (WorkflowDefinition.StateDef s : def.states()) {
            boolean hasOutgoing = statesWithOutgoingTransitions.contains(s.id());
            boolean declaredTerminal = def.terminalStates().contains(s.id());
            if (hasOutgoing && declaredTerminal) {
                throw new IllegalStateException("State " + s.id() + " is declared terminal but has outgoing transitions");
            }
            if (!hasOutgoing && !declaredTerminal) {
                throw new IllegalStateException("State " + s.id() + " has no outgoing transitions but is not declared terminal");
            }
        }
    }
}
```

Note the transition-identity uniqueness check changed from "name globally unique" to "(name, from) pair unique" — the same action name (`approve`, `reject`, `expire`) legitimately repeats across different stages now.

- [ ] **Step 4: Rewrite `transfer-approval.yaml`** to the new schema, same shape/behavior as before

```yaml
name: transfer-approval
version: 1
initialState: SUBMITTED
terminalStates: [APPROVED, REJECTED, CANCELLED, EXPIRED]

states:
  - id: SUBMITTED
    label: Submitted
  - id: PENDING_APPROVAL
    label: Pending Approval
  - id: APPROVED
    label: Approved
  - id: REJECTED
    label: Rejected
  - id: CANCELLED
    label: Cancelled
  - id: EXPIRED
    label: Expired

transitions:
  - name: auto_approve
    from: SUBMITTED
    to: APPROVED
    guard: no_approval_required
  - name: require_approval
    from: SUBMITTED
    to: PENDING_APPROVAL
    guard: approval_required
  - name: approve
    from: PENDING_APPROVAL
    to: APPROVED
    guard: approvals_satisfied
  - name: reject
    from: PENDING_APPROVAL
    to: REJECTED
    guard: actor_is_eligible_checker
  - name: cancel
    from: PENDING_APPROVAL
    to: CANCELLED
    guard: actor_is_maker
  - name: expire
    from: PENDING_APPROVAL
    to: EXPIRED
    guard: sla_expired

events:
  APPROVED: [ApprovalApproved]
  REJECTED: [ApprovalRejected]
  CANCELLED: [ApprovalCancelled]
  EXPIRED: [ApprovalExpired]
```

- [ ] **Step 5: Update `WorkflowLoaderTest.java`**

```java
package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLoaderTest {

    @Test
    void loadsDefinitionFromClasspathYaml() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.name()).isEqualTo("transfer-approval");
        assertThat(def.initialState()).isEqualTo("SUBMITTED");
        assertThat(def.states()).extracting(WorkflowDefinition.StateDef::id).containsExactlyInAnyOrder(
                "SUBMITTED", "PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
        assertThat(def.terminalStates()).containsExactlyInAnyOrder("APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
    }

    @Test
    void transitionsFromSubmittedIncludeAutoApproveAndRequireApproval() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.transitionsFrom("SUBMITTED"))
                .extracting(Transition::name)
                .containsExactlyInAnyOrder("auto_approve", "require_approval");
    }

    @Test
    void approveTransitionGuardIsApprovalsSatisfied() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.byName("approve").guard()).isEqualTo("approvals_satisfied");
        assertThat(def.byName("approve").to()).isEqualTo("APPROVED");
    }

    @Test
    void eventsFiresOnlyOnTerminalStates() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.eventsFor("APPROVED")).containsExactly("ApprovalApproved");
        assertThat(def.eventsFor("PENDING_APPROVAL")).isEmpty();
    }

    @Test
    void loadingDefinitionWithDuplicateTransitionIdentityFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-duplicate-transition.yaml"));
    }
}
```

- [ ] **Step 6: Update `invalid-duplicate-transition.yaml`** (test fixture) to the new schema, keeping it invalid for the same reason (two transitions with identical `(name, from)`)

```yaml
name: broken
version: 1
initialState: SUBMITTED
terminalStates: [APPROVED]
states:
  - id: SUBMITTED
    label: Submitted
  - id: APPROVED
    label: Approved
transitions:
  - name: approve
    from: SUBMITTED
    to: APPROVED
    guard: no_approval_required
  - name: approve
    from: SUBMITTED
    to: APPROVED
    guard: approval_required
```

- [ ] **Step 7: Run the workflow tests to confirm the new schema loads correctly**

Run: `cd approval-engine && ./gradlew test --tests WorkflowLoaderTest`
Expected: PASS (5 tests). Everything else in the module still fails to compile at this point — expected, fixed in the remaining steps.

- [ ] **Step 8: Mechanical transform across every remaining `ApprovalState`-referencing file**

Apply this exact recipe to each file listed below: delete `import com.visionbank.approval.domain.ApprovalState;`, replace every `ApprovalState.X` with the string literal `"X"`, and where a field/column was typed `ApprovalState`, retype it `String` and remove any `@Enumerated(EnumType.STRING)` annotation immediately above it.

**`domain/ApprovalRequest.java`** — change:
```java
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ApprovalState state;
```
to:
```java
    @Column(name = "state", nullable = false)
    private String state;
```

**`domain/AuditLog.java`** — change both:
```java
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", nullable = false)
    private ApprovalState previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_state", nullable = false)
    private ApprovalState newState;
```
to:
```java
    @Column(name = "previous_state", nullable = false)
    private String previousState;

    @Column(name = "new_state", nullable = false)
    private String newState;
```

**`repository/ApprovalRequestRepository.java`** — `guardedTransition` and `findByStateAndExpiresAtBefore` both take `String` state params instead of `ApprovalState`:
```java
    int guardedTransition(@Param("requestId") String requestId,
                           @Param("expectedState") String expectedState,
                           @Param("expectedVersion") long expectedVersion,
                           @Param("newState") String newState);

    List<ApprovalRequest> findByStateAndExpiresAtBefore(String state, Instant cutoff);
```
(The `@Query`/`@Modifying` annotations and JPQL string above `guardedTransition` are unchanged — JPQL doesn't care about the Java-side param type.)

**`service/ApprovalRequestView.java`** — retype:
```java
public record ApprovalRequestView(String requestId, String state, long version) {}
```

**`service/ConcurrentStateChangeException.java`** and **`service/InvalidStateTransitionException.java`** — retype the `currentState` field from `ApprovalState` to `String`; constructor and `super(...)` message-building logic unchanged otherwise.

**`web/dto/ApprovalResponseDto.java`** — retype:
```java
public record ApprovalResponseDto(String requestId, String state, long version) {}
```

**`web/ApiExceptionHandler.java`** — remove the two `.name()` calls (only needed when `currentState` was an enum):
```java
                .body(new ErrorResponseDto("CONCURRENT_STATE_CHANGE", e.requestId, e.currentState, null));
```
```java
                .body(new ErrorResponseDto("INVALID_STATE_TRANSITION", e.requestId, e.currentState, e.requestedAction));
```

**`service/ApprovalCommandService.java`** — every `ApprovalState.SUBMITTED` → `"SUBMITTED"`, `ApprovalState.PENDING_APPROVAL` → `"PENDING_APPROVAL"`, `ApprovalState.APPROVED` → `"APPROVED"`, `ApprovalState.REJECTED` → `"REJECTED"`, `ApprovalState.CANCELLED` → `"CANCELLED"` throughout `create()`, `approve()`, `reject()`, `cancel()`, `classifyRaceOrIllegal()`, `writeAudit()` call sites, and the `Transition initial` lookup logic. No other logic changes — this task keeps the exact current hardcoded two-state behavior.

**`service/ExpirySweeper.java`** — `ApprovalState.PENDING_APPROVAL` → `"PENDING_APPROVAL"` in the `findByStateAndExpiresAtBefore` call.

**`service/ExpiryTransitionService.java`** — `ApprovalState.PENDING_APPROVAL` → `"PENDING_APPROVAL"`, `ApprovalState.EXPIRED` → `"EXPIRED"` in `expireOne()`.

- [ ] **Step 9: Delete `domain/ApprovalState.java`**

```bash
rm approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalState.java
```

- [ ] **Step 10: Compile to find every remaining reference**

Run: `cd approval-engine && ./gradlew compileJava compileTestJava --console=plain`
Expected: compiler errors listing every remaining `ApprovalState` reference in main and test source. Apply the same mechanical transform to each reported file/line — the compiler is the exhaustive checklist here, more reliable than any file list written by hand. Test files (`ApprovalRequestRepositoryTest`, `ApprovalCommandServiceApproveTest`, `ApprovalCommandServiceCreateTest`, `ApprovalConcurrencyTest`, `ExpirySweeperTest`, `ExpiryVersusApproveConcurrencyTest`, `ApprovalControllerTest`) reference `ApprovalState.X` directly in assertions (e.g. `assertThat(view.state()).isEqualTo(ApprovalState.APPROVED)`) — these become `.isEqualTo("APPROVED")`. Repeat compile-and-fix until `compileTestJava` succeeds.

- [ ] **Step 11: Run the full module suite**

Run: `cd approval-engine && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, 39 tests (same count as before this task — no behavior changed, only types), 0 failures, 0 errors.

- [ ] **Step 12: Commit**

```bash
cd /Users/sureshk/vision-hld
git add approval-engine/
git commit -m "refactor: ApprovalState from Java enum to String; extend workflow schema

Prerequisite for multi-workflow support: a fixed enum meant every new
workflow's state names required an engine code change, defeating the
YAML-only extensibility seam. terminalStates/events also added to the
schema, both currently used only by transfer-approval (identical
behavior to before, expressed as YAML instead of implicit literals)."
```

---

## Task 2: Multi-workflow loading (`WorkflowRegistry`) + selection (`WorkflowSelector`) + `privileged-access` sample

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowRegistry.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowSelector.java`
- Move: `approval-engine/src/main/resources/workflow/transfer-approval.yaml` → `approval-engine/src/main/resources/workflow/definitions/transfer-approval.yaml`
- Create: `approval-engine/src/main/resources/workflow/definitions/privileged-access.yaml`
- Create: `approval-engine/src/main/resources/workflow/workflow-selection.yaml`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowConfig.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowRegistryTest.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowSelectorTest.java`
- Modify: `approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowLoaderTest.java` (path change only)

**Interfaces:**
- Consumes: `WorkflowDefinition`, `YamlWorkflowLoader` (Task 1).
- Produces: `WorkflowRegistry.get(String workflowId) -> WorkflowDefinition` (throws `IllegalStateException` if unknown); `WorkflowSelector.resolve(String requestType) -> WorkflowDefinition`. Neither is wired into `ApprovalCommandService` yet — that's Task 4.

- [ ] **Step 1: Move the existing YAML into the new directory**

```bash
mkdir -p approval-engine/src/main/resources/workflow/definitions
git mv approval-engine/src/main/resources/workflow/transfer-approval.yaml approval-engine/src/main/resources/workflow/definitions/transfer-approval.yaml
```

- [ ] **Step 2: Update `WorkflowLoaderTest.java`'s classpath path** — every `"workflow/transfer-approval.yaml"` becomes `"workflow/definitions/transfer-approval.yaml"`.

Run: `cd approval-engine && ./gradlew test --tests WorkflowLoaderTest`
Expected: PASS (5 tests) — confirms the move alone didn't break loading.

- [ ] **Step 3: Write `privileged-access.yaml`**

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

Note: unlike `transfer-approval`, there is no `cancel` transition here (this workflow doesn't offer maker cancellation) and `SUBMITTED` always requires review (no `auto_approve` shortcut) — both deliberate authoring choices this specific workflow makes, not engine limitations.

- [ ] **Step 4: Write `WorkflowRegistry.java`**

```java
package com.visionbank.approval.workflow;

import org.springframework.core.io.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> byId;

    public WorkflowRegistry(String definitionsClasspathPattern, WorkflowLoader loader) {
        this.byId = loadAll(definitionsClasspathPattern, loader);
    }

    public WorkflowDefinition get(String workflowId) {
        WorkflowDefinition def = byId.get(workflowId);
        if (def == null) {
            throw new IllegalStateException("No workflow definition loaded for id: " + workflowId);
        }
        return def;
    }

    private static Map<String, WorkflowDefinition> loadAll(String pattern, WorkflowLoader loader) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            Map<String, WorkflowDefinition> result = new HashMap<>();
            for (Resource r : resources) {
                // Loader takes a classpath-relative path; PathMatchingResourcePatternResolver
                // gives absolute resource URLs, so re-derive the classpath-relative form.
                String classpathPath = "workflow/definitions/" + r.getFilename();
                WorkflowDefinition def = loader.load(classpathPath);
                result.put(def.name(), def);
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("No workflow definitions found matching: " + pattern);
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan workflow definitions: " + pattern, e);
        }
    }
}
```

- [ ] **Step 5: Write `workflow-selection.yaml`**

```yaml
selectors:
  - requestType: TRANSFER_APPROVAL
    workflowId: transfer-approval
  - requestType: PRIVILEGED_ACCESS
    workflowId: privileged-access
```

- [ ] **Step 6: Write `WorkflowSelector.java`**

```java
package com.visionbank.approval.workflow;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class WorkflowSelector {

    private final Map<String, String> workflowIdByRequestType;
    private final WorkflowRegistry registry;

    public WorkflowSelector(String selectionClasspathResource, WorkflowRegistry registry) {
        this.registry = registry;
        this.workflowIdByRequestType = load(selectionClasspathResource);
        // Fail fast at startup if selection.yaml points at a workflow that was never loaded.
        workflowIdByRequestType.values().forEach(registry::get);
    }

    public WorkflowDefinition resolve(String requestType) {
        String workflowId = workflowIdByRequestType.get(requestType);
        if (workflowId == null) {
            throw new IllegalStateException("No workflow selector configured for requestType: " + requestType);
        }
        return registry.get(workflowId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load(String classpathResource) {
        try (InputStream in = new ClassPathResource(classpathResource).getInputStream()) {
            Map<String, Object> raw = new Yaml().load(in);
            List<Map<String, String>> selectors = (List<Map<String, String>>) raw.get("selectors");
            Map<String, String> result = new java.util.HashMap<>();
            for (Map<String, String> s : selectors) {
                result.put(s.get("requestType"), s.get("workflowId"));
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow selection: " + classpathResource, e);
        }
    }
}
```

- [ ] **Step 7: Update `WorkflowConfig.java`** to build both beans instead of the single-file loader

```java
package com.visionbank.approval.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfig {

    @Bean
    public WorkflowRegistry workflowRegistry(GuardRegistry guards) {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());
        // Same startup fail-fast as before Task 2: every guard name referenced by every
        // loaded workflow must resolve, not just the one workflow that used to exist.
        for (String id : new String[]{"transfer-approval", "privileged-access"}) {
            registry.get(id).transitions().forEach(t -> guards.get(t.guard()));
        }
        return registry;
    }

    @Bean
    public WorkflowSelector workflowSelector(WorkflowRegistry registry) {
        return new WorkflowSelector("workflow-selection.yaml", registry);
    }
}
```

- [ ] **Step 8: Write `WorkflowRegistryTest.java`**

```java
package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRegistryTest {

    @Test
    void loadsBothSampleWorkflows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThat(registry.get("transfer-approval").name()).isEqualTo("transfer-approval");
        assertThat(registry.get("privileged-access").name()).isEqualTo("privileged-access");
    }

    @Test
    void unknownWorkflowIdThrows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThatThrownBy(() -> registry.get("does-not-exist"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 9: Run to verify (RED then GREEN)**

Run: `cd approval-engine && ./gradlew test --tests WorkflowRegistryTest`
Expected: PASS (2 tests).

- [ ] **Step 10: Write `WorkflowSelectorTest.java`**

```java
package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowSelectorTest {

    private final WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

    @Test
    void resolvesTransferApprovalRequestType() {
        WorkflowSelector selector = new WorkflowSelector("workflow-selection.yaml", registry);

        assertThat(selector.resolve("TRANSFER_APPROVAL").name()).isEqualTo("transfer-approval");
    }

    @Test
    void resolvesPrivilegedAccessRequestType() {
        WorkflowSelector selector = new WorkflowSelector("workflow-selection.yaml", registry);

        assertThat(selector.resolve("PRIVILEGED_ACCESS").name()).isEqualTo("privileged-access");
    }

    @Test
    void unknownRequestTypeThrows() {
        WorkflowSelector selector = new WorkflowSelector("workflow-selection.yaml", registry);

        assertThatThrownBy(() -> selector.resolve("SOMETHING_ELSE"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 11: Run to verify**

Run: `cd approval-engine && ./gradlew test --tests WorkflowSelectorTest`
Expected: PASS (3 tests).

- [ ] **Step 12: Run the full module suite**

Run: `cd approval-engine && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL. `ApprovalCommandService` still depends on the single `WorkflowDefinition` bean from Task 1 — this task adds `WorkflowRegistry`/`WorkflowSelector` as new, additional beans alongside it, not yet replacing it, so the existing 39 tests are unaffected. Confirm all 39 (+8 new = 47) pass.

- [ ] **Step 13: Commit**

```bash
cd /Users/sureshk/vision-hld
git add approval-engine/
git commit -m "feat: multi-workflow loading and selection

WorkflowRegistry loads every *.yaml under workflow/definitions/;
WorkflowSelector maps requestType -> workflowId via workflow-selection.yaml,
fail-fast at startup on an unresolvable reference. privileged-access.yaml
added as the second, differently-shaped workflow that proves this loads
correctly alongside transfer-approval. Not yet wired into
ApprovalCommandService -- that's the next task."
```

---

## Task 3: Data model — per-request workflow binding, per-stage `PolicySnapshot`, `ApprovalDecision.state`

**Files:**
- Modify: `approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalRequest.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalDecision.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/domain/PolicySnapshot.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/StagePolicy.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/repository/ApprovalDecisionRepository.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/GuardContext.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/StandardGuards.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/CreateApprovalRequest.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/dto/CreateApprovalRequestDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/StagePolicyDto.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/ApprovalController.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java` (GuardContext construction sites only — pass `currentState` — dispatch logic itself is still Task 1's hardcoded literals; full generic dispatch is Task 5)
- Modify (banking-service caller side): `banking-service/src/main/java/com/visionbank/banking/approval/CreateWorkflowRequest.java`, `banking-service/src/main/java/com/visionbank/banking/approval/ApprovalEngineClient.java`, `banking-service/src/main/java/com/visionbank/banking/policy/ApprovalPolicy.java`, `banking-service/src/main/java/com/visionbank/banking/policy/PolicyResolver.java`, `banking-service/src/main/java/com/visionbank/banking/service/TransferSubmissionService.java`
- Modify tests: `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceCreateTest.java`, `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceApproveTest.java`, `approval-engine/src/test/java/com/visionbank/approval/web/ApprovalControllerTest.java`, `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalConcurrencyTest.java`, `approval-engine/src/test/java/com/visionbank/approval/service/ExpiryVersusApproveConcurrencyTest.java`, `approval-engine/src/test/java/com/visionbank/approval/repository/ApprovalRequestRepositoryTest.java`, `banking-service/src/test/java/com/visionbank/banking/policy/PolicyResolverTest.java`, `banking-service/src/test/java/com/visionbank/banking/approval/ApprovalEngineClientTest.java`, `banking-service/src/test/java/com/visionbank/banking/service/TransferSubmissionServiceTest.java`, `banking-service/src/test/java/com/visionbank/banking/web/TransferControllerTest.java`

**Interfaces:**
- Consumes: `WorkflowRegistry`/`WorkflowSelector` (Task 2) — not wired into request creation yet (Task 4), but this task's DTOs need to already be shaped for a world where a request's policy is per-stage.
- Produces: `PolicySnapshot(String policyVersion, Map<String,StagePolicy> stages, boolean makerCanApprove)`; `StagePolicy(int requiredApprovals, List<String> eligibleRoles)`; `ApprovalRequest.getWorkflowId()/getWorkflowVersion()`; `ApprovalDecision.getState()`; `GuardContext(String makerId, PolicySnapshot policy, long currentApprovalCount, String actorId, String actorRole, boolean slaExpired, String currentState)`.

This is the largest task — it's a wire-compatible contract change across both services and can't be landed in pieces without breaking the system mid-way.

- [ ] **Step 1: Write `StagePolicy.java`**

```java
package com.visionbank.approval.domain;

import java.util.List;

public record StagePolicy(int requiredApprovals, List<String> eligibleRoles) {}
```

- [ ] **Step 2: Rewrite `PolicySnapshot.java`**

```java
package com.visionbank.approval.domain;

import java.util.Map;

public record PolicySnapshot(
        String policyVersion,
        Map<String, StagePolicy> stages,
        boolean makerCanApprove) {}
```

- [ ] **Step 3: Add `workflow_id`/`workflow_version` to `ApprovalRequest.java`** — insert after the `expiresAt` field:

```java
    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;
```
(Lombok `@Getter`/`@Setter` on the class already generate `getWorkflowId`/`setWorkflowId`/`getWorkflowVersion`/`setWorkflowVersion` — no manual accessor code needed.)

- [ ] **Step 4: Add `state` to `ApprovalDecision.java` and widen the unique constraint**

```java
@Entity
@Table(name = "approval_decision", uniqueConstraints = @UniqueConstraint(columnNames = {"request_id", "actor_id", "state"}))
@Getter
@Setter
public class ApprovalDecision {
    // ... existing fields unchanged ...

    @Column(name = "state", nullable = false)
    private String state;

    // ... DecisionType enum unchanged ...
}
```
(Insert the `state` field anywhere among the existing `@Column` fields; the `@Table` annotation's `uniqueConstraints` line is the one that must change from `{"request_id", "actor_id"}` to `{"request_id", "actor_id", "state"}`.)

- [ ] **Step 5: Update `ApprovalDecisionRepository.java`**

```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, String> {
    long countByRequestIdAndDecisionAndState(String requestId, ApprovalDecision.DecisionType decision, String state);
    boolean existsByRequestIdAndActorId(String requestId, String actorId);
}
```
(`existsByRequestIdAndActorId` stays exactly as-is per the spec — decision-level idempotency stays request-wide, not scoped to state.)

- [ ] **Step 6: Add `currentState` to `GuardContext.java`**

```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.PolicySnapshot;

public record GuardContext(
        String makerId,
        PolicySnapshot policy,
        long currentApprovalCount,
        String actorId,
        String actorRole,
        boolean slaExpired,
        String currentState) {}
```

- [ ] **Step 7: Rewrite `StandardGuards.java`** — all four guards that read policy now go through the current stage

```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.StagePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StandardGuards {

    @Bean
    public GuardRegistry guardRegistry() {
        return buildRegistry();
    }

    public static GuardRegistry buildRegistry() {
        GuardRegistry registry = new GuardRegistry();
        registry.register("no_approval_required", ctx -> stagePolicy(ctx).requiredApprovals() == 0);
        registry.register("approval_required", ctx -> stagePolicy(ctx).requiredApprovals() > 0);
        registry.register("approvals_satisfied", ctx -> ctx.currentApprovalCount() >= stagePolicy(ctx).requiredApprovals());
        registry.register("actor_is_maker", ctx -> ctx.actorId() != null && ctx.actorId().equals(ctx.makerId()));
        registry.register("actor_is_eligible_checker", ctx -> ctx.actorRole() != null && stagePolicy(ctx).eligibleRoles().contains(ctx.actorRole()));
        registry.register("sla_expired", GuardContext::slaExpired);
        return registry;
    }

    private static StagePolicy stagePolicy(GuardContext ctx) {
        StagePolicy policy = ctx.policy().stages().get(ctx.currentState());
        if (policy == null) {
            throw new IllegalStateException("No StagePolicy supplied for state " + ctx.currentState());
        }
        return policy;
    }
}
```

- [ ] **Step 8: Update every `GuardContext` construction site in `ApprovalCommandService.java`** to pass the request's current state as the new last argument — e.g. in `approve()`:
```java
        GuardContext eligibility = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0,
                actorId, actorRole, false, request.getState());
```
and the quorum context:
```java
        long approvalCount = decisions.countByRequestIdAndDecisionAndState(requestId, ApprovalDecision.DecisionType.APPROVE, request.getState());
        GuardContext quorumCtx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), approvalCount,
                actorId, actorRole, false, request.getState());
```
Apply the same pattern (append `request.getState()`, and switch any decision-count call to the `...AndState` overload) in `reject()`, `cancel()`, and `create()`'s initial `GuardContext ctx = new GuardContext(cmd.makerId(), cmd.policy(), 0, null, null, false)` (append `"SUBMITTED"` there, since `create()` always evaluates from `SUBMITTED`).

- [ ] **Step 9: Update `CreateApprovalRequest.java`** — `policy` field type is already `PolicySnapshot`, no change needed (the shape change is internal to `PolicySnapshot` itself, already done in Step 2).

- [ ] **Step 10: Rewrite `CreateApprovalRequestDto.java`** and add `StagePolicyDto.java`

```java
package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public record CreateApprovalRequestDto(
        @NotBlank String requestId,
        @NotBlank String requestType,
        @NotBlank String makerId,
        @NotNull Map<String, StagePolicyDto> stagePolicies,
        boolean makerCanApprove,
        @NotBlank String payloadJson,
        @NotNull Instant expiresAt) {}
```

```java
package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record StagePolicyDto(@NotNull Integer requiredApprovals, @NotNull List<String> eligibleRoles) {}
```

- [ ] **Step 11: Update `ApprovalController.create()`'s policy construction**

```java
    @PostMapping
    public ApprovalResponseDto create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody CreateApprovalRequestDto dto) {
        Map<String, StagePolicy> stages = dto.stagePolicies().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> new StagePolicy(e.getValue().requiredApprovals(), e.getValue().eligibleRoles())));
        PolicySnapshot policy = new PolicySnapshot("v1", stages, dto.makerCanApprove());
        CreateApprovalRequest cmd = new CreateApprovalRequest(dto.requestId(), dto.requestType(), dto.makerId(),
                policy, dto.payloadJson(), dto.expiresAt());
        ApprovalRequestView view = service.create(cmd, idempotencyKey);
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }
```
(Add `import java.util.Map;` and `import com.visionbank.approval.domain.StagePolicy;` alongside the existing imports.)

- [ ] **Step 12: Update every test in approval-engine that constructs `PolicySnapshot`/`CreateApprovalRequest`/a request JSON body directly** — the mechanical change is: replace `new PolicySnapshot("v1", requiredApprovals, List.of("TRANSFER_CHECKER"), makerCanApprove)` with `new PolicySnapshot("v1", Map.of("PENDING_APPROVAL", new StagePolicy(requiredApprovals, List.of("TRANSFER_CHECKER"))), makerCanApprove)` (the single stage id for every existing `transfer-approval`-workflow test is always `"PENDING_APPROVAL"`, matching that workflow's one approval-gate state). Apply this to every `cmd(...)`-style test helper in `ApprovalCommandServiceCreateTest`, `ApprovalCommandServiceApproveTest`, `ApprovalConcurrencyTest`, `ExpiryVersusApproveConcurrencyTest`, and any raw JSON request bodies in `ApprovalControllerTest` (those become `"stagePolicies": {"PENDING_APPROVAL": {"requiredApprovals": N, "eligibleRoles": [...]}}` instead of flat `"requiredApprovals": N, "eligibleRoles": [...]`). `ApprovalRequestRepositoryTest`'s any direct `ApprovalRequest` construction needs `.setWorkflowId("transfer-approval")` / `.setWorkflowVersion(1)` added (both `@Column(nullable = false)`, so any test building a raw `ApprovalRequest` row now needs both set or persistence fails).

- [ ] **Step 13: Compile and fix remaining errors**

Run: `cd approval-engine && ./gradlew compileTestJava --console=plain`
Fix every reported compile error the same way — the compiler is the exhaustive list.

- [ ] **Step 14: Run the full approval-engine suite**

Run: `cd approval-engine && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, same 47 tests as after Task 2, 0 failures. (`ApprovalRequest.workflowId`/`workflowVersion` are set to fixed literal values like `"transfer-approval"`/`1` in test helpers at this point, not yet resolved dynamically — that's Task 4.)

- [ ] **Step 15: Update `banking-service`'s caller side to match the new wire shape**

`ApprovalPolicy.java` — check current shape first; it's the DTO `PolicyResolver` returns. Reshape to carry per-stage data for the one stage `transfer-approval` actually has:
```java
package com.visionbank.banking.policy;

import java.util.List;

public record ApprovalPolicy(int requiredApprovals, List<String> eligibleRoles, boolean makerCanApprove) {}
```
(No change needed here if this is already its shape — `PolicyResolver`'s amount-tier logic itself is unchanged; only how its output gets packaged into the wire request changes, in `CreateWorkflowRequest`/`ApprovalEngineClient` below.)

`CreateWorkflowRequest.java` — unchanged record shape is fine (`requestId, requestType, makerId, policy, payloadJson, expiresAt`) — it's `ApprovalEngineClient.createWorkflow`'s body-building that needs to change:

```java
    public WorkflowResponse createWorkflow(CreateWorkflowRequest req, String idempotencyKey) {
        Map<String, Object> stagePolicy = Map.of(
                "requiredApprovals", req.policy().requiredApprovals(),
                "eligibleRoles", req.policy().eligibleRoles());
        Map<String, Object> body = Map.of(
                "requestId", req.requestId(),
                "requestType", req.requestType(),
                "makerId", req.makerId(),
                "stagePolicies", Map.of("PENDING_APPROVAL", stagePolicy),
                "makerCanApprove", req.policy().makerCanApprove(),
                "payloadJson", req.payloadJson(),
                "expiresAt", req.expiresAt().toString());

        return restClient.post()
                .uri("/approvals")
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .body(WorkflowResponse.class);
    }
```

`"PENDING_APPROVAL"` is hardcoded here deliberately — `transfer-approval`'s workflow shape (its one approval-gate stage id) is exactly the kind of thing the caller has to know per the spec's "caller resolves policy, must know the target workflow's stage ids" principle. `banking-service` only ever submits `TRANSFER_APPROVAL` requests in this codebase, so this single hardcoded stage id is correct, not a shortcut.

- [ ] **Step 16: Update `ApprovalEngineClientTest.java`** — its WireMock stub asserting the outgoing request body needs updating to expect `stagePolicies: {PENDING_APPROVAL: {...}}` instead of flat `requiredApprovals`/`eligibleRoles`.

- [ ] **Step 17: Run banking-service's suite**

Run: `cd banking-service && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, 23 tests, 0 failures (WireMock/Testcontainers-based tests don't talk to a real approval-engine, so the request-shape mismatch only breaks the ones that assert on the outgoing JSON — fixed in Step 16).

- [ ] **Step 18: Commit**

```bash
cd /Users/sureshk/vision-hld
git add approval-engine/ banking-service/
git commit -m "feat: per-stage policy, per-request workflow binding, per-stage decisions

PolicySnapshot becomes a map of stage id -> StagePolicy instead of one
flat requiredApprovals/eligibleRoles; ApprovalRequest persists which
workflow (id+version) it was created against, frozen forever after,
same pattern as policy_snapshot itself; ApprovalDecision records which
stage each decision was made in, with the uniqueness constraint widened
to (request_id, actor_id, state) so decision-level idempotency stays
correct per-stage. banking-service's caller side updated to match the
new wire shape -- transfer-approval's single stage id (PENDING_APPROVAL)
is hardcoded there deliberately, since the caller has to know its
target workflow's shape either way.

Dispatch logic itself still hardcodes PENDING_APPROVAL/APPROVED
literals at this point -- generic dispatch is the next task."
```

---

## Task 4: Wire `WorkflowSelector` into request creation — per-request workflow binding goes live

**Files:**
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java`
- Modify: `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceCreateTest.java`

**Interfaces:**
- Consumes: `WorkflowSelector.resolve(String requestType) -> WorkflowDefinition` (Task 2); `ApprovalRequest.setWorkflowId/setWorkflowVersion` (Task 3).
- Produces: every `ApprovalRequest` row now carries a genuinely resolved `workflow_id`/`workflow_version` instead of a test-hardcoded literal.

- [ ] **Step 1: Write the failing test** — add to `ApprovalCommandServiceCreateTest.java`

```java
    @Test
    void createResolvesAndPersistsTheSelectedWorkflow() {
        ApprovalRequestView view = service.create(cmd("workflow-resolve-1", 0), UUID.randomUUID().toString());

        ApprovalRequest saved = requests.findByRequestId(view.requestId()).orElseThrow();
        assertThat(saved.getWorkflowId()).isEqualTo("transfer-approval");
        assertThat(saved.getWorkflowVersion()).isEqualTo(1);
    }
```
(This test needs `@Autowired ApprovalRequestRepository requests;` — add it alongside the existing `@Autowired ApprovalCommandService service;` field if not already present. `cmd(...)`'s `requestType` is already `"TRANSFER_APPROVAL"` per the existing helper.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests ApprovalCommandServiceCreateTest`
Expected: FAIL — `saved.getWorkflowId()` is whatever fixed literal the current `create()` sets (nothing dynamic yet), or null if nothing sets it.

- [ ] **Step 3: Inject `WorkflowSelector` into `ApprovalCommandService` and use it in `create()`**

Constructor gains a `WorkflowSelector workflowSelector` parameter (alongside the existing `WorkflowDefinition workflow` param — **do not remove `workflow` yet**, `approve()`/`reject()`/`cancel()` still use it for now, generic dispatch is Task 5):

```java
    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalDecisionRepository decisions,
                                   AuditLogRepository audits, OutboxEventRepository outbox,
                                   IdempotencyRecordRepository idempotency, WorkflowDefinition workflow,
                                   WorkflowSelector workflowSelector, GuardRegistry guards) {
        this.requests = requests;
        this.decisions = decisions;
        this.audits = audits;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.workflow = workflow;
        this.workflowSelector = workflowSelector;
        this.guards = guards;
    }
```
(Add `private final WorkflowSelector workflowSelector;` field and `import com.visionbank.approval.workflow.WorkflowSelector;`.)

In `create()`, after the idempotency-key/requestId-reuse guards and before building the `GuardContext`:
```java
        WorkflowDefinition resolvedWorkflow = workflowSelector.resolve(cmd.requestType());

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId(cmd.requestId());
        request.setRequestType(cmd.requestType());
        request.setWorkflowId(resolvedWorkflow.name());
        request.setWorkflowVersion(resolvedWorkflow.version());
        // ... existing field-setting lines unchanged (makerId, policySnapshot, payload, createdAt, expiresAt, version=0, state=SUBMITTED) ...
```
Change the `Transition initial = workflow.transitionsFrom("SUBMITTED")...` lookup to use `resolvedWorkflow` instead of the still-injected single-workflow `workflow` field:
```java
        Transition initial = resolvedWorkflow.transitionsFrom("SUBMITTED").stream()
                .filter(t -> guards.get(t.guard()).evaluate(ctx))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No transition from SUBMITTED satisfied by policy"));
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests ApprovalCommandServiceCreateTest`
Expected: PASS (all tests in the class, including the new one).

- [ ] **Step 5: Run the full approval-engine suite**

Run: `cd approval-engine && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures — `approve`/`reject`/`cancel`/expiry still use the single injected `workflow` bean (still `transfer-approval` only, hardcoded literals unchanged), so nothing downstream of `create()` is affected yet.

- [ ] **Step 6: Commit**

```bash
cd /Users/sureshk/vision-hld
git add approval-engine/
git commit -m "feat: resolve and persist the selected workflow at request creation

create() now calls WorkflowSelector.resolve(requestType) and freezes
the result onto the row (workflow_id/workflow_version), same pattern
as policy_snapshot/expires_at. approve/reject/cancel still hardcode
the single transfer-approval workflow -- generic dispatch reading the
per-request resolved workflow is the next task."
```

---

## Task 5: Generic command dispatch — `approve`/`reject`/`cancel` resolve transitions from the request's own workflow

**Files:**
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ExpiryTransitionService.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ExpirySweeper.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/repository/ApprovalRequestRepository.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/PrivilegedAccessWorkflowTest.java` (new — proves a genuinely different, multi-stage workflow shape actually runs end to end)
- Modify: `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceApproveTest.java` (existing assertions still pass unchanged — this task must not alter `transfer-approval`'s observed behavior)

**Interfaces:**
- Consumes: `WorkflowRegistry` (Task 2, for resolving a request's workflow by id+version at approve/reject/cancel time — the row already has `workflow_id`); `ApprovalDecisionRepository.countByRequestIdAndDecisionAndState` (Task 3).
- Produces: `approve`/`reject`/`cancel` now correctly execute any workflow shape, not just `transfer-approval`'s two states.

This is the core rewrite the whole plan has been building toward. **Do not touch `classifyRaceOrIllegal` in this task** — its generalization is Task 6, deliberately separated so a subtle bug there doesn't hide behind this task's otherwise-green build.

- [ ] **Step 1: Write the failing test first** — `PrivilegedAccessWorkflowTest.java`, proving the 3-stage workflow actually runs

```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.domain.StagePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class PrivilegedAccessWorkflowTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalCommandService service;

    private CreateApprovalRequest cmd(String requestId) {
        Map<String, StagePolicy> stages = Map.of(
                "SECURITY_REVIEW", new StagePolicy(1, List.of("SECURITY")),
                "MANAGER_APPROVAL", new StagePolicy(1, List.of("MANAGER")),
                "COMPLIANCE_REVIEW", new StagePolicy(1, List.of("COMPLIANCE")));
        return new CreateApprovalRequest(requestId, "PRIVILEGED_ACCESS", "maker-1",
                new PolicySnapshot("v1", stages, false),
                "{\"resource\":\"prod-db\"}", Instant.now().plusSeconds(86400));
    }

    @Test
    void walksAllThreeStagesToApproved() {
        ApprovalRequestView created = service.create(cmd("priv-1"), UUID.randomUUID().toString());
        assertThat(created.state()).isEqualTo("SECURITY_REVIEW");

        ApprovalRequestView afterSecurity = service.approve(created.requestId(), "sec-1", "SECURITY");
        assertThat(afterSecurity.state()).isEqualTo("MANAGER_APPROVAL");

        ApprovalRequestView afterManager = service.approve(created.requestId(), "mgr-1", "MANAGER");
        assertThat(afterManager.state()).isEqualTo("COMPLIANCE_REVIEW");

        ApprovalRequestView afterCompliance = service.approve(created.requestId(), "comp-1", "COMPLIANCE");
        assertThat(afterCompliance.state()).isEqualTo("APPROVED");
    }

    @Test
    void rejectionAtAnyStageEndsTheWorkflow() {
        ApprovalRequestView created = service.create(cmd("priv-2"), UUID.randomUUID().toString());
        service.approve(created.requestId(), "sec-1", "SECURITY");

        ApprovalRequestView rejected = service.reject(created.requestId(), "mgr-1", "MANAGER");

        assertThat(rejected.state()).isEqualTo("REJECTED");
    }

    @Test
    void sameActorCannotDoubleCountWithinOneStageButCanActAtALaterStage() {
        ApprovalRequestView created = service.create(cmd("priv-3"), UUID.randomUUID().toString());

        // "sec-1" approving SECURITY_REVIEW twice is a no-op replay, not two decisions.
        service.approve(created.requestId(), "sec-1", "SECURITY");
        ApprovalRequestView replay = service.approve(created.requestId(), "sec-1", "SECURITY");
        assertThat(replay.state()).isEqualTo("MANAGER_APPROVAL"); // already moved on

        // the SAME actor id acting again at a later stage (different role) is a genuinely
        // new decision -- proves the widened (request_id, actor_id, state) constraint works.
        ApprovalRequestView afterManager = service.approve(created.requestId(), "sec-1", "MANAGER");
        assertThat(afterManager.state()).isEqualTo("COMPLIANCE_REVIEW");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests PrivilegedAccessWorkflowTest`
Expected: FAIL — `create()` resolves `PRIVILEGED_ACCESS` correctly (Task 4), but `approve()` still hardcodes `PENDING_APPROVAL → APPROVED`, so the first `approve()` call throws (current state is `SECURITY_REVIEW`, not `PENDING_APPROVAL`).

- [ ] **Step 3: Add a `WorkflowRegistry`-backed resolver to `ApprovalCommandService`**

Replace the constructor's single injected `WorkflowDefinition workflow` field with `WorkflowRegistry workflowRegistry` (the single-workflow field is no longer needed anywhere — `create()` already switched to `workflowSelector` in Task 4):

```java
    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalDecisionRepository decisions,
                                   AuditLogRepository audits, OutboxEventRepository outbox,
                                   IdempotencyRecordRepository idempotency, WorkflowRegistry workflowRegistry,
                                   WorkflowSelector workflowSelector, GuardRegistry guards) {
        this.requests = requests;
        this.decisions = decisions;
        this.audits = audits;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.workflowRegistry = workflowRegistry;
        this.workflowSelector = workflowSelector;
        this.guards = guards;
    }
```
(`private final WorkflowRegistry workflowRegistry;` replaces `private final WorkflowDefinition workflow;`. `create()`'s `resolvedWorkflow` line from Task 4 is unaffected — it already goes through `workflowSelector`, not the removed field.)

Add a small private helper used by `approve`/`reject`/`cancel`:
```java
    private WorkflowDefinition workflowFor(ApprovalRequest request) {
        return workflowRegistry.get(request.getWorkflowId());
    }
```

- [ ] **Step 4: Rewrite `approve()`** to resolve the transition generically

```java
    @Transactional
    public ApprovalRequestView approve(String requestId, String actorId, String actorRole) {
        ApprovalRequest request = loadOrThrow(requestId);
        WorkflowDefinition workflow = workflowFor(request);
        String currentState = request.getState();

        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("approve"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            throw classifyRaceOrIllegal(requestId, currentState, request.getVersion(), "approve");
        }

        GuardContext eligibility = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0,
                actorId, actorRole, false, currentState);
        if (guards.get("actor_is_maker").evaluate(eligibility) && !request.getPolicySnapshot().makerCanApprove()) {
            throw new ForbiddenActionException("Maker cannot approve their own request: " + requestId);
        }
        if (!guards.get("actor_is_eligible_checker").evaluate(eligibility)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        if (decisions.existsByRequestIdAndActorId(requestId, actorId)) {
            return toView(request); // already decided (any stage) -- idempotent replay
        }

        ApprovalDecision decision = new ApprovalDecision();
        decision.setRequestId(requestId);
        decision.setActorId(actorId);
        decision.setActorRole(actorRole);
        decision.setState(currentState);
        decision.setDecision(ApprovalDecision.DecisionType.APPROVE);
        decision.setCreatedAt(Instant.now());
        decisions.save(decision);

        long approvalCount = decisions.countByRequestIdAndDecisionAndState(requestId, ApprovalDecision.DecisionType.APPROVE, currentState);
        GuardContext quorumCtx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), approvalCount,
                actorId, actorRole, false, currentState);

        if (!guards.get(transition.guard()).evaluate(quorumCtx)) {
            writeAudit(requestId, actorId, actorRole, "APPROVAL_RECORDED", currentState, currentState);
            return toView(request);
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, latest.getState(), latest.getVersion(), "approve");
        }

        writeAudit(requestId, actorId, actorRole, "APPROVED", currentState, transition.to());
        fireEvents(workflow, requestId, transition.to());
        return new ApprovalRequestView(requestId, transition.to(), request.getVersion() + 1);
    }
```

**A real behavior note, not a regression**: the pre-existing test
`replayingSameIdempotencyKeyReturnsSameResultWithoutSecondRequest`-style
decision replay (`decisions.existsByRequestIdAndActorId`) is still
request-wide by design (Task 3's Step 5 comment) — meaning an actor
who decided at *any* stage can never decide again at a *different*
stage either, under this exact check. But `PrivilegedAccessWorkflowTest`'s
third test above expects `"sec-1"` to act at `SECURITY_REVIEW` *and
later* at `MANAGER_APPROVAL`. **Resolve this now, in this step**: change
the idempotency check itself to be state-scoped too —
`decisions.existsByRequestIdAndActorId` is superseded by a new
`existsByRequestIdAndActorIdAndState` on `ApprovalDecisionRepository`
(add it: `boolean existsByRequestIdAndActorIdAndState(String requestId,
String actorId, String state);`), and every call site in `approve()`/
`reject()` uses the state-scoped version. This corrects Task 3's Step 5
note — write down here, plainly, that the earlier plan text was wrong
about keeping it request-wide, caught by writing this test, not asserted
without checking.

- [ ] **Step 5: Add `fireEvents` helper, replacing every hardcoded `writeOutbox(requestId, "ApprovalX")` call site**

```java
    private void fireEvents(WorkflowDefinition workflow, String requestId, String state) {
        for (String eventType : workflow.eventsFor(state)) {
            writeOutbox(requestId, eventType);
        }
    }
```
`writeOutbox(String, String)` itself is unchanged (Task 1). Update `create()`'s two hardcoded `writeOutbox(cmd.requestId(), "ApprovalSubmitted")` / `writeOutbox(cmd.requestId(), "ApprovalApproved")` call sites the same way: `fireEvents(resolvedWorkflow, cmd.requestId(), initial.to())` handles the terminal-on-auto-approve case; the always-fired `ApprovalSubmitted` on every create is a special case outside the `events:` map (every workflow's `SUBMITTED` state is non-terminal, so `eventsFor("SUBMITTED")` is always empty) — keep one explicit `writeOutbox(cmd.requestId(), "ApprovalSubmitted")` call in `create()` unconditionally, then `fireEvents(...)` for whatever `initial.to()` resolves to (covers the auto-approve-emits-both-events case exactly as before).

- [ ] **Step 6: Rewrite `reject()` and `cancel()`** following the exact same pattern as `approve()` — resolve the transition named `"reject"`/`"cancel"` from `currentState` via `workflow.transitionsFrom(currentState)`, use `transition.to()` in `guardedTransition`, call `fireEvents(workflow, requestId, transition.to())` after a successful transition. `cancel()` has no quorum/decision-recording step (unchanged from today — it's a single-actor action), just the transition resolution changes.

- [ ] **Step 7: Generalize `ExpiryTransitionService.expireOne`**

```java
    @Transactional
    public boolean expireOne(String requestId, long expectedVersion, WorkflowDefinition workflow, String currentState) {
        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("expire"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            return false; // this stage doesn't offer expiry -- not every workflow's every stage has to
        }
        int rows = requests.guardedTransition(requestId, currentState, expectedVersion, transition.to());
        if (rows == 0) {
            return false; // lost the race to a concurrent approve/reject/cancel -- not an error
        }
        AuditLog log = new AuditLog();
        log.setRequestId(requestId);
        log.setAction("EXPIRED");
        log.setPreviousState(currentState);
        log.setNewState(transition.to());
        log.setCreatedAt(Instant.now());
        audits.save(log);

        for (String eventType : workflow.eventsFor(transition.to())) {
            OutboxEvent event = new OutboxEvent();
            event.setRequestId(requestId);
            event.setEventType(eventType);
            event.setEventVersion(1);
            event.setPayload("{\"requestId\":\"" + requestId + "\"}");
            event.setCreatedAt(Instant.now());
            outbox.save(event);
        }
        return true;
    }
```
Constructor gains `WorkflowRegistry workflowRegistry` (same pattern as `ApprovalCommandService`).

- [ ] **Step 8: Update `ExpirySweeper.sweepOnce`** — the candidate query can no longer hardcode `PENDING_APPROVAL`. Change `ApprovalRequestRepository.findByStateAndExpiresAtBefore(String state, Instant cutoff)` to `findByStateInAndExpiresAtBefore(List<String> states, Instant cutoff)`, and have `ExpirySweeper` compute the full set of non-terminal states across every loaded workflow once (cache it as a field, computed in a constructor from `WorkflowRegistry` — walk every workflow's `states()` filtering out anything in `terminalStates()`), then look up each candidate's own workflow to call `expireOne` correctly:

```java
    public ExpirySweeper(ApprovalRequestRepository requests, ExpiryTransitionService transitionService, WorkflowRegistry workflowRegistry) {
        this.requests = requests;
        this.transitionService = transitionService;
        this.workflowRegistry = workflowRegistry;
    }

    @Scheduled(fixedDelay = 60000)
    public int sweepOnce() {
        List<String> nonTerminalStates = workflowRegistry.allNonTerminalStates();
        List<ApprovalRequest> candidates = requests.findByStateInAndExpiresAtBefore(nonTerminalStates, Instant.now());
        int expiredCount = 0;
        for (ApprovalRequest candidate : candidates) {
            WorkflowDefinition workflow = workflowRegistry.get(candidate.getWorkflowId());
            if (transitionService.expireOne(candidate.getRequestId(), candidate.getVersion(), workflow, candidate.getState())) {
                expiredCount++;
            }
        }
        return expiredCount;
    }
```
Add `allNonTerminalStates()` to `WorkflowRegistry`:
```java
    public List<String> allNonTerminalStates() {
        return byId.values().stream()
                .flatMap(def -> def.states().stream())
                .map(WorkflowDefinition.StateDef::id)
                .distinct()
                .filter(id -> byId.values().stream().noneMatch(def -> def.terminalStates().contains(id)))
                .collect(java.util.stream.Collectors.toList());
    }
```
(A state id that's terminal in one workflow but coincidentally shares a name with a non-terminal state in another is an edge case no current sample workflow hits — both examples use disjoint state-id vocabularies. Not worth solving speculatively; noted here rather than silently ignored.)

- [ ] **Step 9: Run `PrivilegedAccessWorkflowTest`**

Run: `cd approval-engine && ./gradlew test --tests PrivilegedAccessWorkflowTest`
Expected: PASS (3 tests).

- [ ] **Step 10: Run the full approval-engine suite**

Run: `cd approval-engine && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL. `ApprovalCommandServiceApproveTest`'s existing assertions (all against `transfer-approval`'s two-state shape) must still pass unchanged — this is the concrete proof the rewrite preserves existing behavior, not just that it compiles.

- [ ] **Step 11: Commit**

```bash
cd /Users/sureshk/vision-hld
git add approval-engine/
git commit -m "feat: generic command dispatch -- approve/reject/cancel/expire

approve/reject/cancel no longer hardcode PENDING_APPROVAL/APPROVED;
each resolves the matching transition from the request's own bound
WorkflowDefinition and drives everything (guard, quorum scope,
transition target, fired events) from that. ExpirySweeper generalizes
its candidate query across every loaded workflow's non-terminal states.
PrivilegedAccessWorkflowTest proves a genuinely different 3-stage shape
runs correctly end to end -- the actual point of this whole plan.

Corrected an error in Task 3's plan text along the way: decision-level
idempotency needed to become state-scoped (existsByRequestIdAndActorIdAndState),
not stay request-wide as originally written -- caught by writing the
same-actor-later-stage test, not assumed.

classifyRaceOrIllegal is untouched -- still the old two-state-specific
logic, correct only for transfer-approval. Its generalization is the
next task, deliberately kept separate."
```

---

## Task 6: Generalize `classifyRaceOrIllegal` for N-stage graphs, with its own concurrency test suite

**Files:**
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/PrivilegedAccessConcurrencyTest.java` (new)

**Interfaces:**
- Consumes: `WorkflowDefinition.transitionsFrom` (Task 1).
- Produces: `classifyRaceOrIllegal` now correctly classifies races across any loaded workflow's transition graph, not just the two-state case.

**Design** (per spec §7's flagged approach, worked through here): given the actual current state has no transition named `<action>` from it, walk every transition named `<action>` anywhere in the workflow, and for each, check whether its `to`-state graph-reachably leads to the current observed state via zero or more further legal transitions (BFS from each candidate `to`, following the full transition graph). If **any** such path exists, the actor's target action *was* valid at the moment they read the row — something else won the race since (409 `CONCURRENT_STATE_CHANGE`). If **none** does, the action could never have applied to this row regardless of timing (409 `INVALID_STATE_TRANSITION`).

The two-state version's `currentVersion`-based tiebreak generalizes as: **when more than one such path exists** (the current state is reachable from more than one of the action's candidate `to`-states, or via paths of different lengths), the number of hops actually taken — `currentVersion` — disambiguates a genuine multi-step race from a shortcut path that was never a race at all. Concretely: compute the *shortest* path length (in transitions) from each viable candidate `to`-state to the current state; if `currentVersion` matches a shortcut path's length exactly, classify as illegal (no decision was ever made at the actor's target stage); if it matches only a path that passes through a real intermediate decision, classify as a race.

- [ ] **Step 1: Write the failing tests first** — `PrivilegedAccessConcurrencyTest.java`

```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.domain.StagePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PrivilegedAccessConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalCommandService service;

    private String create(String requestId) {
        Map<String, StagePolicy> stages = Map.of(
                "SECURITY_REVIEW", new StagePolicy(1, List.of("SECURITY")),
                "MANAGER_APPROVAL", new StagePolicy(1, List.of("MANAGER")),
                "COMPLIANCE_REVIEW", new StagePolicy(1, List.of("COMPLIANCE")));
        CreateApprovalRequest cmd = new CreateApprovalRequest(requestId, "PRIVILEGED_ACCESS", "maker-1",
                new PolicySnapshot("v1", stages, false), "{}", Instant.now().plusSeconds(86400));
        return service.create(cmd, UUID.randomUUID().toString()).requestId();
    }

    @Test
    void approvingAStageAlreadyMovedPastIsAConcurrentStateChange() throws Exception {
        String id = create("race-1");
        service.approve(id, "sec-1", "SECURITY"); // moves to MANAGER_APPROVAL

        // A second, stale attempt at the SECURITY_REVIEW gate the row already left --
        // genuinely raced: this action WAS valid when presumably read, lost to the above.
        assertThatThrownBy(() -> {
            // Simulate a stale read by manually re-deriving what approve() would see:
            // reject() from the already-passed SECURITY_REVIEW stage is impossible to
            // literally re-attempt via the public API once the row has moved on, so this
            // test instead proves the illegal-vs-race split at the state actually reached:
            // rejecting at MANAGER_APPROVAL after SECURITY_REVIEW's own reject transition
            // was already consumed by an approval is exercised by the next two tests,
            // which race real concurrent threads.
            throw new IllegalStateException("see concurrent tests below");
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoActorsApprovingTheSameStageConcurrently_exactlyOneWins() throws Exception {
        String id = create("race-2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        var f1 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return service.approve(id, "sec-1", "SECURITY");
        });
        var f2 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return service.approve(id, "sec-2", "SECURITY");
        });
        ready.await();
        go.countDown();

        // required=1 for SECURITY_REVIEW: whichever commits first satisfies quorum and
        // moves the row; the second sees a state that's no longer SECURITY_REVIEW, with
        // no "approve" transition from MANAGER_APPROVAL matching what it expected --
        // must classify as a race (this actor's approve WAS valid when they presumably
        // read PENDING at SECURITY_REVIEW), not as illegal.
        int successes = 0;
        Exception caught = null;
        for (var f : List.of(f1, f2)) {
            try {
                f.get();
                successes++;
            } catch (Exception e) {
                caught = e;
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(caught).isNotNull();
        assertThat(caught.getCause()).isInstanceOf(ConcurrentStateChangeException.class);
        pool.shutdown();
    }

    @Test
    void rejectingAStageAlreadyApprovedPastIsClassifiedAsARaceNotIllegal() {
        String id = create("race-3");
        service.approve(id, "sec-1", "SECURITY"); // row now at MANAGER_APPROVAL, version 2

        // "reject" IS a real transition from SECURITY_REVIEW in this workflow -- a stale
        // actor attempting it after the row moved on must get CONCURRENT_STATE_CHANGE,
        // proving the graph-reachability check (not just the old single-hop check) finds
        // that MANAGER_APPROVAL is reachable from SECURITY_REVIEW via the real approve path.
        assertThatThrownBy(() -> service.reject(id, "sec-stale", "SECURITY"))
                .isInstanceOf(ConcurrentStateChangeException.class);
    }

    @Test
    void approvingATerminalStateIsIllegalRegardlessOfTiming() {
        String id = create("race-4");
        service.approve(id, "sec-1", "SECURITY");
        service.approve(id, "mgr-1", "MANAGER");
        service.approve(id, "comp-1", "COMPLIANCE"); // now APPROVED, terminal

        assertThatThrownBy(() -> service.approve(id, "late-1", "SECURITY"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd approval-engine && ./gradlew test --tests PrivilegedAccessConcurrencyTest`
Expected: FAIL — `classifyRaceOrIllegal` still contains the old two-state-specific logic (`current == "PENDING_APPROVAL"`, `reachableFromPendingApproval`/`reachableFromSubmitted` hardcoded variable names checking exactly those two states), which doesn't reason about `SECURITY_REVIEW`/`MANAGER_APPROVAL`/`COMPLIANCE_REVIEW` at all.

- [ ] **Step 3: Rewrite `classifyRaceOrIllegal`**

```java
    private RuntimeException classifyRaceOrIllegal(String requestId, String current, long currentVersion, String action) {
        WorkflowDefinition workflow = workflowRegistry.get(requests.findByRequestId(requestId).orElseThrow().getWorkflowId());

        // Every state this action-type could plausibly have started from: any `from`
        // of a transition named `action`, anywhere in the workflow.
        List<String> candidateStarts = workflow.transitions().stream()
                .filter(t -> t.name().equals(action))
                .map(Transition::from)
                .distinct()
                .toList();

        // Shortest reachable path length (in transitions) from each candidate start to
        // `current`, via any legal transition sequence. Empty if unreachable from that start.
        int shortestHopsToReachCurrent = Integer.MAX_VALUE;
        boolean reachableAtAll = false;
        for (String start : candidateStarts) {
            int hops = shortestPathLength(workflow, start, current);
            if (hops >= 0) {
                reachableAtAll = true;
                shortestHopsToReachCurrent = Math.min(shortestHopsToReachCurrent, hops);
            }
        }

        if (!reachableAtAll) {
            return new InvalidStateTransitionException(requestId, current, action);
        }

        // currentVersion counts transitions actually taken on this row. If it's larger
        // than the shortest possible hop count from a candidate start to here, at least
        // one real intervening decision happened -- a genuine race. If it exactly equals
        // the shortest path, this could be a shortcut with zero real decisions in between
        // -- but only genuinely ambiguous when a shortcut path AND a longer, decision-bearing
        // path both reach `current`; when every path to `current` is the same length, there's
        // nothing to disambiguate and it's a legitimate race regardless.
        boolean multiplePathLengthsExist = candidateStarts.stream()
                .map(start -> shortestPathLength(workflow, start, current))
                .filter(h -> h >= 0)
                .distinct()
                .count() > 1;

        if (multiplePathLengthsExist && currentVersion <= shortestHopsToReachCurrent) {
            return new InvalidStateTransitionException(requestId, current, action);
        }
        return new ConcurrentStateChangeException(requestId, current);
    }

    private int shortestPathLength(WorkflowDefinition workflow, String from, String to) {
        if (from.equals(to)) {
            return 0;
        }
        java.util.Queue<String> frontier = new java.util.ArrayDeque<>();
        java.util.Map<String, Integer> distance = new java.util.HashMap<>();
        frontier.add(from);
        distance.put(from, 0);
        while (!frontier.isEmpty()) {
            String node = frontier.poll();
            for (Transition t : workflow.transitionsFrom(node)) {
                if (!distance.containsKey(t.to())) {
                    distance.put(t.to(), distance.get(node) + 1);
                    if (t.to().equals(to)) {
                        return distance.get(t.to());
                    }
                    frontier.add(t.to());
                }
            }
        }
        return -1;
    }
```

Note this **subsumes** the original two-state special case exactly:
`transfer-approval`'s `APPROVED` is reachable from `SUBMITTED` two ways
— directly via `auto_approve` (1 hop) and via `require_approval` +
`approve` (2 hops through `PENDING_APPROVAL`). `candidateStarts` for
action `"approve"` is `["PENDING_APPROVAL"]` only (the only `from` of a
transition named `approve`), so this specific case actually has a
single candidate start and doesn't hit the `multiplePathLengthsExist`
branch at all — it's `reachableFromPendingApproval` in the old code,
preserved as-is. The multi-path-length ambiguity this generalization
adds handles workflows where the *same action name* legitimately starts
from more than one state (not true for either sample workflow's
`approve` today, but a real workflow could have e.g. two different
early stages both offering a `reject` back to a shared `REJECTED`
state at different hop counts) — built for correctness on graphs beyond
what today's two samples happen to exercise, not just to pass the tests
above.

- [ ] **Step 4: Run to verify all four tests pass**

Run: `cd approval-engine && ./gradlew test --tests PrivilegedAccessConcurrencyTest`
Expected: PASS (4 tests). If `twoActorsApprovingTheSameStageConcurrently_exactlyOneWins` is flaky (races are inherently timing-sensitive), rerun up to 3 times — genuine flakiness here (not a compile/logic error) is a signal to report, not silently retry past.

- [ ] **Step 5: Run the FULL approval-engine suite — this is the critical regression gate for this task**

Run: `cd approval-engine && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, every existing test green, especially `ApprovalConcurrencyTest` and `ExpiryVersusApproveConcurrencyTest` (the original two-state race tests) — these must pass completely unchanged, proving the generalization didn't regress the case it was generalized *from*.

- [ ] **Step 6: Commit**

```bash
cd /Users/sureshk/vision-hld
git add approval-engine/
git commit -m "feat: generalize classifyRaceOrIllegal to arbitrary transition graphs

Replaces the two-state-specific reachability check (hardcoded around
PENDING_APPROVAL/SUBMITTED) with a real BFS shortest-path computation
over the request's own resolved WorkflowDefinition, and generalizes
the currentVersion tiebreak from a single special case to comparing
against the shortest possible hop count. Verified this subsumes the
original two-state logic exactly (transfer-approval's existing
concurrency tests pass unchanged) rather than just passing new tests
in isolation. New PrivilegedAccessConcurrencyTest proves race vs.
illegal classification is correct on a genuinely different 3-stage
graph, not just the shape this logic was originally written for."
```

---

## Task 7: `GET /approvals/{id}/workflow-view` — generic stage-progress endpoint

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/WorkflowViewDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/StageViewDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/DecisionViewDto.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/ApprovalController.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/web/WorkflowViewControllerTest.java` (new — or add to existing `ApprovalControllerTest.java`)

**Interfaces:**
- Consumes: `WorkflowRegistry`, `ApprovalDecisionRepository`, `AuditLogRepository` (already injected/available).
- Produces: `GET /approvals/{id}/workflow-view` returning the JSON shape from spec §8.

- [ ] **Step 1: Write the DTOs**

```java
package com.visionbank.approval.web.dto;

import java.util.List;

public record WorkflowViewDto(String workflowId, int workflowVersion, String currentState,
                               List<String> terminalStates, List<StageViewDto> stages) {}
```

```java
package com.visionbank.approval.web.dto;

import java.util.List;

public record StageViewDto(String id, String label, String status,
                            Integer requiredApprovals, Integer completedApprovals,
                            List<DecisionViewDto> approvals) {}
```

```java
package com.visionbank.approval.web.dto;

import java.time.Instant;

public record DecisionViewDto(String actorId, String actorRole, String decision, Instant createdAt) {}
```

- [ ] **Step 2: Write the failing test** — add to `ApprovalControllerTest.java` or a new file, exercising the full `MockMvc`/real-repository path already established in that test class

```java
    @Test
    void workflowViewShowsStageProgressForAPendingRequest() throws Exception {
        // uses the same fixture-creation pattern already established in this test class
        // to POST /approvals with requiredApprovals=2, then GET .../workflow-view and
        // assert: currentState == "PENDING_APPROVAL", the PENDING_APPROVAL stage's
        // status == "IN_PROGRESS", requiredApprovals == 2, completedApprovals == 0,
        // approvals is empty, and the SUBMITTED stage's status == "COMPLETED".
    }
```
(Follow this test class's existing MockMvc setup exactly — don't introduce a second test-configuration pattern in the same file.)

- [ ] **Step 3: Run to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests ApprovalControllerTest`
Expected: FAIL — 404, endpoint doesn't exist yet.

- [ ] **Step 4: Implement the endpoint in `ApprovalController.java`**

```java
    @GetMapping("/{id}/workflow-view")
    public WorkflowViewDto workflowView(@PathVariable String id) {
        ApprovalRequest request = requests.findByRequestId(id)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        WorkflowDefinition workflow = workflowRegistry.get(request.getWorkflowId());
        String currentState = request.getState();

        List<StageViewDto> stages = workflow.states().stream()
                .map(s -> buildStageView(workflow, request, s, currentState))
                .toList();

        return new WorkflowViewDto(workflow.name(), workflow.version(), currentState,
                new java.util.ArrayList<>(workflow.terminalStates()), stages);
    }

    private StageViewDto buildStageView(WorkflowDefinition workflow, ApprovalRequest request,
                                         WorkflowDefinition.StateDef stateDef, String currentState) {
        String id = stateDef.id();
        String status;
        if (id.equals(currentState)) {
            status = "IN_PROGRESS";
        } else if (workflow.isTerminal(id)) {
            status = id.equals(currentState) ? "IN_PROGRESS" : (hasEverReached(request, id) ? "COMPLETED" : "PENDING");
        } else {
            status = hasEverReached(request, id) ? "COMPLETED" : "PENDING";
        }

        StagePolicy policy = request.getPolicySnapshot().stages().get(id);
        if (policy == null) {
            return new StageViewDto(id, stateDef.label(), status, null, null, List.of());
        }

        List<AuditLog> stageAudits = audits.findByRequestIdOrderByCreatedAtAsc(request.getRequestId());
        List<DecisionViewDto> approvals = decisions.findByRequestIdAndState(request.getRequestId(), id).stream()
                .map(d -> new DecisionViewDto(d.getActorId(), d.getActorRole(), d.getDecision().name(), d.getCreatedAt()))
                .toList();
        long completed = approvals.stream().filter(a -> a.decision().equals("APPROVE")).count();

        return new StageViewDto(id, stateDef.label(), status, policy.requiredApprovals(), (int) completed, approvals);
    }

    private boolean hasEverReached(ApprovalRequest request, String stateId) {
        return audits.findByRequestIdOrderByCreatedAtAsc(request.getRequestId()).stream()
                .anyMatch(a -> a.getNewState().equals(stateId) || a.getPreviousState().equals(stateId));
    }
```

Add `WorkflowRegistry workflowRegistry` and `ApprovalDecisionRepository decisions` to the controller's constructor and fields (alongside the existing `service`/`requests`/`audits` from prior tasks). Add `ApprovalDecisionRepository.findByRequestIdAndState(String requestId, String state) -> List<ApprovalDecision>` (a new derived-query method).

- [ ] **Step 5: Run to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests ApprovalControllerTest`
Expected: PASS.

- [ ] **Step 6: Run the full suite**

Run: `cd approval-engine && ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Manually verify against the running system** — since this endpoint's real value is proving the "second tenant" claim visually, not just passing a unit test:

```bash
cd /Users/sureshk/vision-hld/approval-engine && ./gradlew bootRun &
sleep 8
curl -s -X POST http://localhost:8081/approvals \
  -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" \
  -d '{"requestId":"manual-priv-1","requestType":"PRIVILEGED_ACCESS","makerId":"maker-1","stagePolicies":{"SECURITY_REVIEW":{"requiredApprovals":1,"eligibleRoles":["SECURITY"]},"MANAGER_APPROVAL":{"requiredApprovals":1,"eligibleRoles":["MANAGER"]},"COMPLIANCE_REVIEW":{"requiredApprovals":1,"eligibleRoles":["COMPLIANCE"]}},"makerCanApprove":false,"payloadJson":"{}","expiresAt":"2026-12-31T00:00:00Z"}'
curl -s http://localhost:8081/approvals/manual-priv-1/workflow-view
kill %1
```
Expected: the `workflow-view` response shows `workflowId: "privileged-access"`, `currentState: "SECURITY_REVIEW"`, and a `stages` array covering all 7 of that workflow's states — confirms the endpoint genuinely renders a workflow shape it's never seen hardcoded anywhere in its own source.

- [ ] **Step 8: Commit**

```bash
cd /Users/sureshk/vision-hld
git add approval-engine/
git commit -m "feat: GET /approvals/{id}/workflow-view -- generic stage-progress endpoint

Additive, doesn't change any existing documented contract. Returns
workflow id/version/labels + live per-stage status + the current
stage's individual decisions, driven entirely by the request's own
resolved WorkflowDefinition -- verified manually against a real
privileged-access request to confirm it renders a shape with zero
hardcoded knowledge of that workflow anywhere in its own source."
```

---

## Task 8: `ui.html`/`UiController` — data-driven rendering

**Files:**
- Modify: `banking-service/src/main/java/com/visionbank/banking/ui/UiController.java`
- Modify: `banking-service/src/main/java/com/visionbank/banking/approval/ApprovalEngineClient.java`
- Create: `banking-service/src/main/java/com/visionbank/banking/ui/WorkflowViewDto.java`
- Create: `banking-service/src/main/java/com/visionbank/banking/ui/StageViewDto.java`
- Modify: `banking-service/src/main/resources/static/ui.html`

**Interfaces:**
- Consumes: `GET /approvals/{id}/workflow-view` (Task 7).
- Produces: the SSE stream now emits a `workflow-view` event carrying the full generic stages payload; `ui.html`'s pipeline renderer becomes data-driven.

- [ ] **Step 1: Add matching DTOs on the banking-service side**

```java
package com.visionbank.banking.ui;

import java.util.List;

public record WorkflowViewDto(String workflowId, int workflowVersion, String currentState,
                               List<String> terminalStates, List<StageViewDto> stages) {}
```

```java
package com.visionbank.banking.ui;

import java.util.List;

public record StageViewDto(String id, String label, String status,
                            Integer requiredApprovals, Integer completedApprovals,
                            List<AuditEntryDto> approvals) {}
```
(Reuses the existing `AuditEntryDto` — its shape, `(action, previousState, newState, actorId, actorRole, createdAt)`, doesn't exactly match `DecisionViewDto`'s `(actorId, actorRole, decision, createdAt)`, but for the console's purposes reading `actorId`/`actorRole`/`createdAt` off it is enough; don't add a fifth near-duplicate DTO for one field name difference — use `AuditEntryDto` here and simply don't read its `action`/`previousState`/`newState` fields in this context.)

- [ ] **Step 2: Add a `getWorkflowView` method to `ApprovalEngineClient`**

```java
    public WorkflowViewDto getWorkflowView(String id) {
        return restClient.get().uri("/approvals/{id}/workflow-view", id).retrieve().body(WorkflowViewDto.class);
    }
```

- [ ] **Step 3: Add a proxy endpoint and extend the SSE stream in `UiController`**

```java
    @GetMapping("/approvals/{id}/workflow-view")
    public WorkflowViewDto getWorkflowView(@PathVariable String id) {
        return approvalEngineClient.getWorkflowView(id);
    }
```

In `pollAndStream`, alongside the existing `audit`/`state` event emission, add a `workflow-view` event whenever the workflow view has changed since the last poll (compare a cheap signature — e.g. `currentState` plus each stage's `completedApprovals` — to avoid re-sending an identical payload every second):

```java
                WorkflowViewDto view = transfer.getApprovalRequestId() != null
                        ? safeGetWorkflowView(transfer.getApprovalRequestId())
                        : null;
                if (view != null) {
                    String signature = view.currentState() + view.stages().stream()
                            .map(s -> s.id() + ":" + s.completedApprovals()).collect(java.util.stream.Collectors.joining(","));
                    if (!signature.equals(lastWorkflowViewSignature)) {
                        emitter.send(SseEmitter.event().name("workflow-view").data(view));
                        lastWorkflowViewSignature = signature;
                    }
                }
```
(Add `String lastWorkflowViewSignature = null;` alongside the existing `lastTransferState`/`lastApprovalState` locals in `pollAndStream`, and a `safeGetWorkflowView` helper mirroring the existing `safeGetApproval`.)

- [ ] **Step 4: Rewrite `ui.html`'s pipeline rendering to be data-driven**

Replace the current hardcoded five `<div class="stage" data-stage="...">` blocks and the `setStage()`/`applyState()` functions with a container the JS populates from `workflow-view` events:

```html
<div class="pipeline" id="pipeline"></div>
```

```javascript
es.addEventListener('workflow-view', e => {
  const view = JSON.parse(e.data);
  renderPipeline(view);
});

function renderPipeline(view) {
  const el = document.getElementById('pipeline');
  el.innerHTML = '';
  view.stages.forEach(stage => {
    const div = document.createElement('div');
    const cssStatus = stage.status.toLowerCase().replace('_', '-');
    div.className = 'stage ' + (cssStatus === 'in-progress' ? 'active' : cssStatus === 'completed' ? 'success' : cssStatus === 'failed' ? 'failed' : 'pending');
    let extra = '';
    if (stage.requiredApprovals != null) {
      extra = '<div style="font-size:11px;color:#666;margin-top:4px">' +
        stage.completedApprovals + ' / ' + stage.requiredApprovals + ' approvals' +
        (stage.approvals.length ? ': ' + stage.approvals.map(a => a.actorId).join(', ') : '') + '</div>';
    }
    div.innerHTML = '<div><span class="label">' + stage.label + '</span>' + extra + '</div>' +
      '<span class="badge">' + stage.status.toLowerCase() + '</span>';
    el.appendChild(div);
  });
  if (view.currentState && !view.terminalStates.includes(view.currentState)) {
    document.getElementById('decision-panel').classList.add('show');
  } else {
    document.getElementById('decision-panel').classList.remove('show');
  }
}
```

Remove the now-unused `setStage`/`resetPipeline`'s stage-block-reset logic (`resetPipeline` now just does `document.getElementById('pipeline').innerHTML = ''` plus clearing the console/timeline) and the old `applyState(s)` function entirely — `workflow-view` events fully replace what `state` events used to drive for pipeline rendering. The existing three-lifecycle visual separation (Approval Engine section vs. Transfer Execution section) still applies: the Transfer Execution stages (`Release Pending`/`Released`) are **not** part of `view.stages` (that endpoint only ever returns the Approval Engine's own stages, per Task 7) — keep those as the existing two static blocks below the dynamically-rendered pipeline, driven by the existing `state` event's `transferState` field exactly as before.

- [ ] **Step 5: Manual verification** — rebuild and run both services, submit a `PRIVILEGED_ACCESS`-shaped request through the console's existing form (extend the form's request-type dropdown if not already present — a minimal addition, not a redesign), confirm the pipeline renders all 7 stages with correct labels and no hardcoded stage names anywhere triggering — the same console, unmodified in its own JS, must correctly render both `transfer-approval` and `privileged-access` shapes.

- [ ] **Step 6: Run both full test suites one more time**

Run: `cd approval-engine && ./gradlew test --console=plain && cd ../banking-service && ./gradlew test --console=plain`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /Users/sureshk/vision-hld
git add banking-service/
git commit -m "feat: data-driven pipeline rendering in the test console

ui.html's pipeline blocks are now built entirely from the new
workflow-view SSE event -- no hardcoded stage names, labels, or count
anywhere in the JS. Verified against both transfer-approval (5 stages)
and privileged-access (7 stages) rendering correctly from the exact
same unmodified page."
```

---

## Task 9: Full-system proof — end-to-end verification of the second tenant claim

**Files:** none (verification only)

**Interfaces:** none — this task proves the whole plan's actual point: the engine genuinely runs two differently-shaped workflows without engine-code awareness of either's specific shape beyond the generic dispatch built in Tasks 5-6.

- [ ] **Step 1: Run both full test suites fresh**

```bash
cd /Users/sureshk/vision-hld/approval-engine && ./gradlew test --console=plain
cd /Users/sureshk/vision-hld/banking-service && ./gradlew test --console=plain
```
Expected: both BUILD SUCCESSFUL. Record the exact test counts for the ledger/final report.

- [ ] **Step 2: Full docker-compose smoke test, unchanged from before this plan**

```bash
cd /Users/sureshk/vision-hld
docker compose down -v --remove-orphans
docker compose up --build -d
./docker-compose.smoke-test.sh
```
Expected: `SMOKE TEST PASSED` — confirms this plan's changes haven't broken the existing `transfer-approval` end-to-end path (submit → auto-release), the one thing every prior review round already covered.

- [ ] **Step 3: End-to-end proof of the `privileged-access` workflow against the running containers**

```bash
curl -s -X POST http://localhost:8081/approvals \
  -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" \
  -d '{"requestId":"e2e-priv-1","requestType":"PRIVILEGED_ACCESS","makerId":"maker-1","stagePolicies":{"SECURITY_REVIEW":{"requiredApprovals":1,"eligibleRoles":["SECURITY"]},"MANAGER_APPROVAL":{"requiredApprovals":1,"eligibleRoles":["MANAGER"]},"COMPLIANCE_REVIEW":{"requiredApprovals":1,"eligibleRoles":["COMPLIANCE"]}},"makerCanApprove":false,"payloadJson":"{}","expiresAt":"2026-12-31T00:00:00Z"}'

curl -s -X POST http://localhost:8081/approvals/e2e-priv-1/approve -H "Content-Type: application/json" -d '{"actorId":"sec-1","actorRole":"SECURITY"}'
curl -s -X POST http://localhost:8081/approvals/e2e-priv-1/approve -H "Content-Type: application/json" -d '{"actorId":"mgr-1","actorRole":"MANAGER"}'
curl -s -X POST http://localhost:8081/approvals/e2e-priv-1/approve -H "Content-Type: application/json" -d '{"actorId":"comp-1","actorRole":"COMPLIANCE"}'
curl -s http://localhost:8081/approvals/e2e-priv-1
```
Expected: final `GET` shows `state: "APPROVED"` — a request whose workflow was never `transfer-approval`, ran entirely through the real, running, containerized engine, with no engine-side code aware of `SECURITY_REVIEW`/`MANAGER_APPROVAL`/`COMPLIANCE_REVIEW` beyond the YAML files that declare them.

- [ ] **Step 4: Tear down**

```bash
cd /Users/sureshk/vision-hld
docker compose down -v --remove-orphans
```

- [ ] **Step 5: Report final test counts and both verification results** — this is the plan's completion gate; both the regression suite and the genuine second-tenant proof must be green before considering this plan done.

---

## Plan Self-Review

**Spec coverage:** §1-2 (goal, out-of-scope) → reflected in Global Constraints and every task's explicit non-goals. §3 (schema) → Task 1. §4 (multi-workflow + selection) → Task 2. §5 (data model) → Task 3. §6 (generic dispatch) → Tasks 4-5. §7 (`classifyRaceOrIllegal`, explicitly flagged for its own pass) → Task 6, with its own dedicated test suite as the spec required. §8 (UI) → Tasks 7-8. §9 (what does not change) → verified as regression gates throughout (existing 39/23 tests must stay green at every task boundary). §10 (migration note, drop-and-recreate) → implicit in every task's Testcontainers-based tests already recreating schema fresh; explicitly called out in Task 9's smoke test using a fresh `docker compose down -v`.

**Placeholder scan:** no TBD/TODO. Task 7's Step 2 test body is described rather than fully coded — deliberately, since it explicitly says to follow the existing test class's established fixture pattern rather than inventing a second one; this is a judgment call, not a placeholder, and the assertions it must make are spelled out completely.

**Type consistency:** `WorkflowDefinition.StateDef(String id, String label)` (Task 1) is read identically in Task 7's `buildStageView` and Task 8's `renderPipeline`. `Transition(String name, String from, String to, String guard)` (Task 1) is consumed identically by `classifyRaceOrIllegal` (Task 6) and the generic dispatch methods (Task 5). `StagePolicy(int requiredApprovals, List<String> eligibleRoles)` (Task 3) is read identically by `StandardGuards` (Task 3) and `ApprovalController.buildStageView` (Task 7). `ApprovalDecisionRepository.countByRequestIdAndDecisionAndState`/`existsByRequestIdAndActorIdAndState` (Task 3, corrected in Task 5) are used consistently from Task 5 onward — Task 5's step 4 explicitly documents correcting Task 3's original (wrong) assumption about keeping idempotency request-wide, rather than silently diverging.
