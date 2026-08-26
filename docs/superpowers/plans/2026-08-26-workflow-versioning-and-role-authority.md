# Workflow Versioning and Role Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `WorkflowRegistry` version-keyed, embed the resolved `WorkflowDefinition` directly in each request's `policy_snapshot`, move `allowedRoles`/`requiredApprovals` from caller-supplied JSON into the workflow YAML's transitions, and remove the caller-supplied `makerCanApprove`/`requestType`-based workflow selection — so every request is self-contained (never re-reads the live registry after creation) and role/quorum authority lives in one versioned, immutable place instead of being reinventable per-request.

**Architecture:** `banking-service`'s `PolicyResolver` picks an exact `(workflowId, workflowVersion)` pair by amount tier; `approval-engine` looks that pair up in a `(workflowId, version)`-keyed `WorkflowRegistry` **only at creation time**, freezes the resolved `WorkflowDefinition` into `policy_snapshot.workflow`, and every later operation (`approve`/`reject`/`cancel`/`workflow-view`/expiry) reads that frozen copy — never the registry again. `allowedRoles` on a transition is checked intrinsically by the engine (not an opt-in guard); a new `actor_is_not_maker` guard replaces the old `makerCanApprove` caller flag for workflows that want to forbid self-approval.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL (via Testcontainers in tests), SnakeYAML, Jackson 3 (`tools.jackson`), JUnit 5, AssertJ, WireMock (banking-service only).

**Spec:** `docs/superpowers/specs/2026-08-26-workflow-versioning-and-role-authority-design.md` (extends `docs/superpowers/specs/2026-08-26-multi-stage-workflow-engine-design.md`)

## Global Constraints

- No backward compatibility shims: `ddl-auto: update` + local dev/test data only, no production users — schema and API shape change freely (spec §9).
- Guards stay a fixed, named registry — never build an expression/rule engine (spec §14 of the original request, preserved).
- `approve`/`reject`/`cancel` stay three separate REST endpoints — no generic `POST /{id}/{action}` dispatch is introduced.
- Every existing behavior for `privileged-access` and the (now three) transfer workflows must be re-provable by tests after the refactor — this is a refactor of *where* data lives, not a feature removal, except where the spec explicitly says a capability (caller-supplied `stagePolicies`/`makerCanApprove`) is deleted.

---

## Task 1: Engine core — workflow model, guards, and command dispatch

This is one large, atomic task by necessity: `Transition`'s field shape, `PolicySnapshot`'s shape, and `WorkflowRegistry`'s method signature all change together, and every file that touches any of them (`ApprovalCommandService`, `ApprovalController`, `ExpirySweeper`, and ~10 test files) must be updated in the same commit to keep `approval-engine` compiling. Steps are grouped by file/concern; run the full `approval-engine` test suite only once at the end of the task (intermediate steps will not compile in isolation — that's expected for a change this deeply coupled).

**Files:**
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/Transition.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/YamlWorkflowLoader.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowConfig.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowRegistry.java`
- Delete: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowSelector.java`
- Delete: `approval-engine/src/main/resources/workflow/workflow-selection.yaml`
- Delete: `approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowSelectorTest.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/GuardContext.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/workflow/StandardGuards.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/domain/PolicySnapshot.java`
- Delete: `approval-engine/src/main/java/com/visionbank/approval/domain/StagePolicy.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/CreateApprovalRequest.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ExpirySweeper.java`
- Delete: `approval-engine/src/main/resources/workflow/definitions/transfer-approval.yaml`
- Create: `approval-engine/src/main/resources/workflow/definitions/transfer-auto-release.yaml`
- Create: `approval-engine/src/main/resources/workflow/definitions/transfer-single-checker.yaml`
- Create: `approval-engine/src/main/resources/workflow/definitions/transfer-high-value.yaml`
- Modify: `approval-engine/src/main/resources/workflow/definitions/privileged-access.yaml`
- Create: `approval-engine/src/main/resources/workflow/definitions/privileged-access-v2.yaml`
- Modify: `approval-engine/src/test/resources/workflow/invalid-duplicate-transition.yaml`
- Create: `approval-engine/src/test/resources/workflow/invalid-zero-required-approvals.yaml`
- Modify/Test: `WorkflowLoaderTest.java`, `WorkflowRegistryTest.java`, `StandardGuardsTest.java`, `ApprovalCommandServiceCreateTest.java`, `ApprovalCommandServiceApproveTest.java`, `PrivilegedAccessWorkflowTest.java`, `PrivilegedAccessConcurrencyTest.java`, `ApprovalConcurrencyTest.java`, `ExpiryVersusApproveConcurrencyTest.java`, `ExpirySweeperTest.java`, `ApprovalRequestRepositoryTest.java` (all under `approval-engine/src/test/java/com/visionbank/approval/...`)

**Interfaces:**
- Consumes: nothing from earlier tasks (this is the first task).
- Produces: `Transition(String name, String from, String to, List<String> guards, List<String> allowedRoles, Integer requiredApprovals)`; `WorkflowRegistry.get(String workflowId, int version)`; `PolicySnapshot(String policyVersion, WorkflowDefinition workflow)`; `CreateApprovalRequest(String requestId, String requestType, String makerId, String workflowId, int workflowVersion, String policyVersion, String payloadJson, Instant expiresAt)` — Task 2 (`ApprovalController`) and Task 3 (banking-service) both construct/consume these exact types.

- [ ] **Step 1: Rewrite `Transition.java`**

```java
package com.visionbank.approval.workflow;

import java.util.List;

public record Transition(String name, String from, String to, List<String> guards,
                          List<String> allowedRoles, Integer requiredApprovals) {}
```

- [ ] **Step 2: Rewrite `YamlWorkflowLoader.java`**

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

            List<Transition> transitions = ((List<Map<String, Object>>) raw.get("transitions")).stream()
                    .map(this::toTransition)
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

    @SuppressWarnings("unchecked")
    private Transition toTransition(Map<String, Object> t) {
        List<String> guards = t.containsKey("guards") ? (List<String>) t.get("guards") : List.of();
        List<String> allowedRoles = t.containsKey("allowedRoles") ? (List<String>) t.get("allowedRoles") : List.of();
        Integer requiredApprovals = (Integer) t.get("requiredApprovals");
        return new Transition((String) t.get("name"), (String) t.get("from"), (String) t.get("to"),
                guards, allowedRoles, requiredApprovals);
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
            if (t.requiredApprovals() != null && t.requiredApprovals() < 1) {
                throw new IllegalStateException("Transition " + t.name() + " from " + t.from()
                        + " has requiredApprovals " + t.requiredApprovals() + ", must be >= 1 when present");
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

- [ ] **Step 3: Rewrite `WorkflowConfig.java`** (drop the `workflowSelector` bean entirely; guard-validation loop iterates `t.guards()`)

```java
package com.visionbank.approval.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfig {

    @Bean
    public WorkflowRegistry workflowRegistry(GuardRegistry guards) {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());
        registry.all().forEach(def -> def.transitions().forEach(t -> t.guards().forEach(guards::get)));
        return registry;
    }
}
```

- [ ] **Step 4: Rewrite `WorkflowRegistry.java`** (composite `(workflowId, version)` key; exact-match `get`; duplicate-version detection at load time)

```java
package com.visionbank.approval.workflow;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorkflowRegistry {

    public record WorkflowKey(String workflowId, int version) {}

    private final Map<WorkflowKey, WorkflowDefinition> byKey;

    public WorkflowRegistry(String definitionsClasspathPattern, WorkflowLoader loader) {
        this.byKey = loadAll(definitionsClasspathPattern, loader);
    }

    public WorkflowDefinition get(String workflowId, int version) {
        WorkflowKey key = new WorkflowKey(workflowId, version);
        WorkflowDefinition def = byKey.get(key);
        if (def == null) {
            throw new IllegalStateException("No workflow definition loaded for " + workflowId + ":" + version);
        }
        return def;
    }

    public Collection<WorkflowDefinition> all() {
        return byKey.values();
    }

    // Used by ExpirySweeper to build its candidate query across every loaded workflow
    // version, not just one hardcoded state.
    public List<String> allNonTerminalStates() {
        return byKey.values().stream()
                .flatMap(def -> def.states().stream())
                .map(WorkflowDefinition.StateDef::id)
                .distinct()
                .filter(id -> byKey.values().stream().noneMatch(def -> def.terminalStates().contains(id)))
                .collect(java.util.stream.Collectors.toList());
    }

    private static Map<WorkflowKey, WorkflowDefinition> loadAll(String pattern, WorkflowLoader loader) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            Map<WorkflowKey, WorkflowDefinition> result = new HashMap<>();
            for (Resource r : resources) {
                String classpathPath = "workflow/definitions/" + r.getFilename();
                WorkflowDefinition def = loader.load(classpathPath);
                WorkflowKey key = new WorkflowKey(def.name(), def.version());
                if (result.containsKey(key)) {
                    throw new IllegalStateException("Duplicate workflow definition for " + key.workflowId() + ":" + key.version());
                }
                result.put(key, def);
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

- [ ] **Step 5: Delete `WorkflowSelector.java`, `workflow-selection.yaml`, and `WorkflowSelectorTest.java`**

```bash
rm approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowSelector.java
rm approval-engine/src/main/resources/workflow/workflow-selection.yaml
rm approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowSelectorTest.java
```

- [ ] **Step 6: Rewrite `GuardContext.java`** (drop `policy`; add `requiredApprovals`, sourced from the matched transition by the caller)

```java
package com.visionbank.approval.workflow;

public record GuardContext(
        String makerId,
        long currentApprovalCount,
        String actorId,
        String actorRole,
        boolean slaExpired,
        String currentState,
        Integer requiredApprovals) {}
```

- [ ] **Step 7: Rewrite `StandardGuards.java`** (delete `no_approval_required`/`approval_required`/`actor_is_eligible_checker` and the `stagePolicy()` helper — see spec §3.5 for why the first two have nothing left to read; add `actor_is_not_maker`; `approvals_satisfied` reads `ctx.requiredApprovals()` directly)

```java
package com.visionbank.approval.workflow;

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
        registry.register("approvals_satisfied", ctx -> ctx.currentApprovalCount() >= ctx.requiredApprovals());
        registry.register("actor_is_maker", ctx -> ctx.actorId() != null && ctx.actorId().equals(ctx.makerId()));
        registry.register("actor_is_not_maker", ctx -> ctx.actorId() == null || !ctx.actorId().equals(ctx.makerId()));
        registry.register("sla_expired", GuardContext::slaExpired);
        return registry;
    }
}
```

- [ ] **Step 8: Rewrite `PolicySnapshot.java`; delete `StagePolicy.java`**

```java
package com.visionbank.approval.domain;

import com.visionbank.approval.workflow.WorkflowDefinition;

public record PolicySnapshot(String policyVersion, WorkflowDefinition workflow) {}
```

```bash
rm approval-engine/src/main/java/com/visionbank/approval/domain/StagePolicy.java
```

`PolicySnapshotConverter.java` needs no code change — it's a generic Jackson `AttributeConverter<PolicySnapshot, String>`; `WorkflowDefinition` is already a plain nested-record type and serializes/deserializes the same way `PolicySnapshot` itself always has. Verified by the integration tests later in this task actually persisting and reloading a request.

- [ ] **Step 9: Delete `transfer-approval.yaml`; create the three fixed-quorum transfer workflows (spec §3.5 — `no_approval_required`/`approval_required` had nothing left to read once quorum is fixed per workflow version, so the old single workflow's 0/1/2-approval branching becomes three separate workflows matching `PolicyResolver`'s existing three amount tiers)**

```bash
rm approval-engine/src/main/resources/workflow/definitions/transfer-approval.yaml
```

`approval-engine/src/main/resources/workflow/definitions/transfer-auto-release.yaml`:
```yaml
name: transfer-auto-release
version: 1
initialState: SUBMITTED
terminalStates: [APPROVED]

states:
  - id: SUBMITTED
    label: Submitted
  - id: APPROVED
    label: Approved

transitions:
  - name: auto_approve
    from: SUBMITTED
    to: APPROVED
    guards: []

events:
  APPROVED: [ApprovalApproved]
```

`approval-engine/src/main/resources/workflow/definitions/transfer-single-checker.yaml`:
```yaml
name: transfer-single-checker
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
  - name: require_approval
    from: SUBMITTED
    to: PENDING_APPROVAL
    guards: []
  - name: approve
    from: PENDING_APPROVAL
    to: APPROVED
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [TRANSFER_CHECKER]
    requiredApprovals: 1
  - name: reject
    from: PENDING_APPROVAL
    to: REJECTED
    allowedRoles: [TRANSFER_CHECKER]
  - name: cancel
    from: PENDING_APPROVAL
    to: CANCELLED
    guards: [actor_is_maker]
  - name: expire
    from: PENDING_APPROVAL
    to: EXPIRED
    guards: [sla_expired]

events:
  APPROVED: [ApprovalApproved]
  REJECTED: [ApprovalRejected]
  CANCELLED: [ApprovalCancelled]
  EXPIRED: [ApprovalExpired]
```

`approval-engine/src/main/resources/workflow/definitions/transfer-high-value.yaml`: identical to `transfer-single-checker.yaml` above except `name: transfer-high-value` and the approve transition's `requiredApprovals: 2`:
```yaml
name: transfer-high-value
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
  - name: require_approval
    from: SUBMITTED
    to: PENDING_APPROVAL
    guards: []
  - name: approve
    from: PENDING_APPROVAL
    to: APPROVED
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [TRANSFER_CHECKER]
    requiredApprovals: 2
  - name: reject
    from: PENDING_APPROVAL
    to: REJECTED
    allowedRoles: [TRANSFER_CHECKER]
  - name: cancel
    from: PENDING_APPROVAL
    to: CANCELLED
    guards: [actor_is_maker]
  - name: expire
    from: PENDING_APPROVAL
    to: EXPIRED
    guards: [sla_expired]

events:
  APPROVED: [ApprovalApproved]
  REJECTED: [ApprovalRejected]
  CANCELLED: [ApprovalCancelled]
  EXPIRED: [ApprovalExpired]
```

- [ ] **Step 10: Update `privileged-access.yaml`; add a `privileged-access-v2.yaml` (exercises real multi-version registry resolution — spec §10's registry-versioning test requirement)**

`approval-engine/src/main/resources/workflow/definitions/privileged-access.yaml`:
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
    guards: []
  - name: approve
    from: SECURITY_REVIEW
    to: MANAGER_APPROVAL
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [SECURITY]
    requiredApprovals: 1
  - name: approve
    from: MANAGER_APPROVAL
    to: COMPLIANCE_REVIEW
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [MANAGER]
    requiredApprovals: 1
  - name: approve
    from: COMPLIANCE_REVIEW
    to: APPROVED
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [COMPLIANCE]
    requiredApprovals: 1
  - name: reject
    from: SECURITY_REVIEW
    to: REJECTED
    allowedRoles: [SECURITY]
  - name: reject
    from: MANAGER_APPROVAL
    to: REJECTED
    allowedRoles: [MANAGER]
  - name: reject
    from: COMPLIANCE_REVIEW
    to: REJECTED
    allowedRoles: [COMPLIANCE]
  - name: expire
    from: SECURITY_REVIEW
    to: EXPIRED
    guards: [sla_expired]
  - name: expire
    from: MANAGER_APPROVAL
    to: EXPIRED
    guards: [sla_expired]
  - name: expire
    from: COMPLIANCE_REVIEW
    to: EXPIRED
    guards: [sla_expired]

events:
  APPROVED: [ApprovalApproved]
  REJECTED: [ApprovalRejected]
  EXPIRED: [ApprovalExpired]
```

`approval-engine/src/main/resources/workflow/definitions/privileged-access-v2.yaml` (identical except `version: 2` and `SECURITY_REVIEW`'s approve transition requires 2 approvals instead of 1):
```yaml
name: privileged-access
version: 2
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
    guards: []
  - name: approve
    from: SECURITY_REVIEW
    to: MANAGER_APPROVAL
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [SECURITY]
    requiredApprovals: 2
  - name: approve
    from: MANAGER_APPROVAL
    to: COMPLIANCE_REVIEW
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [MANAGER]
    requiredApprovals: 1
  - name: approve
    from: COMPLIANCE_REVIEW
    to: APPROVED
    guards: [approvals_satisfied, actor_is_not_maker]
    allowedRoles: [COMPLIANCE]
    requiredApprovals: 1
  - name: reject
    from: SECURITY_REVIEW
    to: REJECTED
    allowedRoles: [SECURITY]
  - name: reject
    from: MANAGER_APPROVAL
    to: REJECTED
    allowedRoles: [MANAGER]
  - name: reject
    from: COMPLIANCE_REVIEW
    to: REJECTED
    allowedRoles: [COMPLIANCE]
  - name: expire
    from: SECURITY_REVIEW
    to: EXPIRED
    guards: [sla_expired]
  - name: expire
    from: MANAGER_APPROVAL
    to: EXPIRED
    guards: [sla_expired]
  - name: expire
    from: COMPLIANCE_REVIEW
    to: EXPIRED
    guards: [sla_expired]

events:
  APPROVED: [ApprovalApproved]
  REJECTED: [ApprovalRejected]
  EXPIRED: [ApprovalExpired]
```

- [ ] **Step 11: Update the two loader test fixtures**

`approval-engine/src/test/resources/workflow/invalid-duplicate-transition.yaml` (only the `guard:` → `guards:` syntax changes; the guard names deleted in Step 7 are irrelevant here since the duplicate-identity check fires before any guard-name is ever resolved):
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
    guards: []
  - name: approve
    from: SUBMITTED
    to: APPROVED
    guards: []
```

`approval-engine/src/test/resources/workflow/invalid-zero-required-approvals.yaml` (new fixture, backs the new structural-validation test in Step 12):
```yaml
name: broken-quorum
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
    guards: []
    requiredApprovals: 0
```

- [ ] **Step 12: Rewrite `WorkflowLoaderTest.java`**

```java
package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLoaderTest {

    @Test
    void loadsDefinitionFromClasspathYaml() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        assertThat(def.name()).isEqualTo("transfer-single-checker");
        assertThat(def.initialState()).isEqualTo("SUBMITTED");
        assertThat(def.states()).extracting(WorkflowDefinition.StateDef::id).containsExactlyInAnyOrder(
                "SUBMITTED", "PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
        assertThat(def.terminalStates()).containsExactlyInAnyOrder("APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
    }

    @Test
    void transitionsFromSubmittedIncludeRequireApproval() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        assertThat(def.transitionsFrom("SUBMITTED"))
                .extracting(Transition::name)
                .containsExactly("require_approval");
    }

    @Test
    void approveTransitionCarriesGuardsRolesAndQuorum() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        Transition approve = def.transitionsFrom("PENDING_APPROVAL").stream()
                .filter(t -> t.name().equals("approve"))
                .findFirst()
                .orElseThrow();

        assertThat(approve.guards()).containsExactly("approvals_satisfied", "actor_is_not_maker");
        assertThat(approve.allowedRoles()).containsExactly("TRANSFER_CHECKER");
        assertThat(approve.requiredApprovals()).isEqualTo(1);
        assertThat(approve.to()).isEqualTo("APPROVED");
    }

    @Test
    void eventsFiresOnlyOnTerminalStates() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        assertThat(def.eventsFor("APPROVED")).containsExactly("ApprovalApproved");
        assertThat(def.eventsFor("PENDING_APPROVAL")).isEmpty();
    }

    @Test
    void loadingDefinitionWithDuplicateTransitionIdentityFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-duplicate-transition.yaml"));
    }

    @Test
    void requiredApprovalsBelowOneFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-zero-required-approvals.yaml"));
    }
}
```

- [ ] **Step 13: Rewrite `WorkflowRegistryTest.java`**

```java
package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRegistryTest {

    @Test
    void loadsAllSampleWorkflowsIncludingMultipleVersionsOfTheSameId() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThat(registry.get("transfer-auto-release", 1).name()).isEqualTo("transfer-auto-release");
        assertThat(registry.get("transfer-single-checker", 1).name()).isEqualTo("transfer-single-checker");
        assertThat(registry.get("transfer-high-value", 1).name()).isEqualTo("transfer-high-value");
        assertThat(registry.get("privileged-access", 1).version()).isEqualTo(1);
        assertThat(registry.get("privileged-access", 2).version()).isEqualTo(2);
    }

    @Test
    void unknownWorkflowIdThrows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThatThrownBy(() -> registry.get("does-not-exist", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void knownWorkflowIdWithUnknownVersionThrows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThatThrownBy(() -> registry.get("privileged-access", 99))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

`WorkflowConfigTest.java` needs no change — it still calls `config.workflowRegistry(emptyRegistry)` with the same single-`GuardRegistry`-argument signature and asserts the same `"No guard registered"` failure; run it as a regression check at the end of this task, don't edit it.

- [ ] **Step 14: Rewrite `StandardGuardsTest.java`**

```java
package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StandardGuardsTest {

    private final GuardRegistry registry = StandardGuards.buildRegistry();

    @Test
    void approvalsSatisfiedComparesCountToRequired() {
        GuardContext under = new GuardContext("maker-1", 1, null, null, false, "PENDING_APPROVAL", 2);
        GuardContext at = new GuardContext("maker-1", 2, null, null, false, "PENDING_APPROVAL", 2);
        assertThat(registry.get("approvals_satisfied").evaluate(under)).isFalse();
        assertThat(registry.get("approvals_satisfied").evaluate(at)).isTrue();
    }

    @Test
    void actorIsMakerComparesActorIdToMakerId() {
        GuardContext ctx = new GuardContext("maker-1", 0, "maker-1", "MAKER", false, "PENDING_APPROVAL", 1);
        GuardContext other = new GuardContext("maker-1", 0, "checker-1", "TRANSFER_CHECKER", false, "PENDING_APPROVAL", 1);
        assertThat(registry.get("actor_is_maker").evaluate(ctx)).isTrue();
        assertThat(registry.get("actor_is_maker").evaluate(other)).isFalse();
    }

    @Test
    void actorIsNotMakerIsTheNegation() {
        GuardContext maker = new GuardContext("maker-1", 0, "maker-1", "TRANSFER_CHECKER", false, "PENDING_APPROVAL", 1);
        GuardContext notMaker = new GuardContext("maker-1", 0, "checker-1", "TRANSFER_CHECKER", false, "PENDING_APPROVAL", 1);
        assertThat(registry.get("actor_is_not_maker").evaluate(maker)).isFalse();
        assertThat(registry.get("actor_is_not_maker").evaluate(notMaker)).isTrue();
    }

    @Test
    void slaExpiredReflectsContextFlag() {
        GuardContext expired = new GuardContext("maker-1", 0, null, null, true, "PENDING_APPROVAL", 1);
        GuardContext notExpired = new GuardContext("maker-1", 0, null, null, false, "PENDING_APPROVAL", 1);
        assertThat(registry.get("sla_expired").evaluate(expired)).isTrue();
        assertThat(registry.get("sla_expired").evaluate(notExpired)).isFalse();
    }
}
```

- [ ] **Step 15: Rewrite `CreateApprovalRequest.java`** (drop the caller-built `PolicySnapshot`; add explicit `workflowId`/`workflowVersion`/`policyVersion`)

```java
package com.visionbank.approval.service;

import java.time.Instant;

public record CreateApprovalRequest(
        String requestId,
        String requestType,
        String makerId,
        String workflowId,
        int workflowVersion,
        String policyVersion,
        String payloadJson,
        Instant expiresAt) {}
```

- [ ] **Step 16: Rewrite `ApprovalCommandService.java`**

```java
package com.visionbank.approval.service;

import tools.jackson.databind.ObjectMapper;
import com.visionbank.approval.domain.*;
import com.visionbank.approval.repository.*;
import com.visionbank.approval.workflow.GuardContext;
import com.visionbank.approval.workflow.GuardRegistry;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

@Service
public class ApprovalCommandService {

    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;
    private final IdempotencyRecordRepository idempotency;
    private final WorkflowRegistry workflowRegistry;
    private final GuardRegistry guards;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalDecisionRepository decisions,
                                   AuditLogRepository audits, OutboxEventRepository outbox,
                                   IdempotencyRecordRepository idempotency, WorkflowRegistry workflowRegistry,
                                   GuardRegistry guards) {
        this.requests = requests;
        this.decisions = decisions;
        this.audits = audits;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.workflowRegistry = workflowRegistry;
        this.guards = guards;
    }

    // Every operation on an EXISTING request reads its own frozen copy -- never the
    // live registry. workflowRegistry is injected solely for create()'s one lookup.
    private WorkflowDefinition workflowFor(ApprovalRequest request) {
        return request.getPolicySnapshot().workflow();
    }

    private boolean isRoleAllowed(Transition transition, String actorRole) {
        return transition.allowedRoles().isEmpty() || transition.allowedRoles().contains(actorRole);
    }

    @Transactional
    public ApprovalRequestView create(CreateApprovalRequest cmd, String idempotencyKey) {
        String hash = hash(cmd);
        Optional<IdempotencyRecord> existing = idempotency.findById(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            ApprovalRequest replayed = requests.findByRequestId(existing.get().getRequestId()).orElseThrow();
            return toView(replayed);
        }

        if (requests.findByRequestId(cmd.requestId()).isPresent()) {
            // Same requestId under a fresh idempotency key would otherwise merge-overwrite the
            // existing row's state/version/policy_snapshot via JPA's detached-entity save path —
            // reject rather than silently reset an in-flight or already-decided request.
            throw new IdempotencyConflictException(cmd.requestId());
        }

        WorkflowDefinition resolvedWorkflow;
        try {
            resolvedWorkflow = workflowRegistry.get(cmd.workflowId(), cmd.workflowVersion());
        } catch (IllegalStateException e) {
            throw new InvalidRequestException("Unknown workflow " + cmd.workflowId() + ":" + cmd.workflowVersion());
        }

        PolicySnapshot policy = new PolicySnapshot(cmd.policyVersion(), resolvedWorkflow);

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId(cmd.requestId());
        request.setRequestType(cmd.requestType());
        request.setWorkflowId(resolvedWorkflow.name());
        request.setWorkflowVersion(resolvedWorkflow.version());
        request.setMakerId(cmd.makerId());
        request.setPolicySnapshot(policy);
        request.setPayload(cmd.payloadJson());
        request.setCreatedAt(Instant.now());
        request.setExpiresAt(cmd.expiresAt());
        request.setVersion(0L);
        request.setState("SUBMITTED");

        // Each workflow's SUBMITTED state now has exactly one outgoing transition (spec
        // §3.5 — quorum is fixed per workflow version, so there's nothing left to branch
        // on per-request). Still evaluated generically via guards -- an empty guards list
        // always passes -- so a future workflow that DOES want conditional routing on
        // something guard-worthy (not required-approvals-count) still works unmodified.
        GuardContext ctx = new GuardContext(cmd.makerId(), 0, null, null, false, "SUBMITTED", null);
        Transition initial = resolvedWorkflow.transitionsFrom("SUBMITTED").stream()
                .filter(t -> t.guards().stream().allMatch(g -> guards.get(g).evaluate(ctx)))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        "No transition from SUBMITTED satisfied for workflow " + resolvedWorkflow.name()));

        request.setState(initial.to());
        request.setVersion(1L);
        requests.save(request);

        writeAudit(cmd.requestId(), null, null, "SUBMITTED", "SUBMITTED", initial.to());
        writeOutbox(cmd.requestId(), "ApprovalSubmitted");
        fireEvents(resolvedWorkflow, cmd.requestId(), initial.to());

        IdempotencyRecord record = new IdempotencyRecord();
        record.setKey(idempotencyKey);
        record.setCommandType("CREATE");
        record.setRequestId(cmd.requestId());
        record.setRequestHash(hash);
        record.setResult("{\"state\":\"" + initial.to() + "\"}");
        record.setCreatedAt(Instant.now());
        idempotency.save(record);

        return toView(request);
    }

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
            throw classifyRaceOrIllegal(requestId, workflow, currentState, request.getVersion(), "approve");
        }

        if (!isRoleAllowed(transition, actorRole)) {
            // Ineligible for the CURRENT stage doesn't necessarily mean this call is bogus: a
            // client that already legitimately decided at an earlier stage may retry with the
            // (now stale) role it used back then, after the request has since moved on to a
            // stage that role doesn't qualify for. Treat that specific case as a harmless
            // stale replay rather than a hard Forbidden; an actor with no decision anywhere on
            // this request is still genuinely rejected.
            if (decisions.existsByRequestIdAndActorIdAndActorRole(requestId, actorId, actorRole)) {
                return toView(request);
            }
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        GuardContext preDecisionCtx = new GuardContext(request.getMakerId(), 0, actorId, actorRole, false,
                currentState, transition.requiredApprovals());
        for (String guardName : transition.guards()) {
            if (guardName.equals("approvals_satisfied")) {
                continue; // evaluated after recording the decision, with the real count
            }
            if (!guards.get(guardName).evaluate(preDecisionCtx)) {
                throw new ForbiddenActionException("Maker cannot approve their own request: " + requestId);
            }
        }

        if (decisions.existsByRequestIdAndActorIdAndState(requestId, actorId, currentState)) {
            return toView(request); // already decided at this stage — idempotent replay of the decision itself
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

        if (transition.guards().contains("approvals_satisfied")) {
            GuardContext quorumCtx = new GuardContext(request.getMakerId(), approvalCount, actorId, actorRole, false,
                    currentState, transition.requiredApprovals());
            if (!guards.get("approvals_satisfied").evaluate(quorumCtx)) {
                writeAudit(requestId, actorId, actorRole, "APPROVAL_RECORDED", currentState, currentState);
                return toView(request);
            }
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, workflow, latest.getState(), latest.getVersion(), "approve");
        }

        writeAudit(requestId, actorId, actorRole, "APPROVED", currentState, transition.to());
        fireEvents(workflow, requestId, transition.to());
        return new ApprovalRequestView(requestId, transition.to(), request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView reject(String requestId, String actorId, String actorRole) {
        ApprovalRequest request = loadOrThrow(requestId);
        WorkflowDefinition workflow = workflowFor(request);
        String currentState = request.getState();

        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("reject"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            throw classifyRaceOrIllegal(requestId, workflow, currentState, request.getVersion(), "reject");
        }

        if (!isRoleAllowed(transition, actorRole)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        GuardContext ctx = new GuardContext(request.getMakerId(), 0, actorId, actorRole, false,
                currentState, transition.requiredApprovals());
        for (String guardName : transition.guards()) {
            if (!guards.get(guardName).evaluate(ctx)) {
                throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
            }
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, workflow, latest.getState(), latest.getVersion(), "reject");
        }
        writeAudit(requestId, actorId, actorRole, "REJECTED", currentState, transition.to());
        fireEvents(workflow, requestId, transition.to());
        return new ApprovalRequestView(requestId, transition.to(), request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView cancel(String requestId, String actorId) {
        ApprovalRequest request = loadOrThrow(requestId);
        WorkflowDefinition workflow = workflowFor(request);
        String currentState = request.getState();

        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("cancel"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            throw classifyRaceOrIllegal(requestId, workflow, currentState, request.getVersion(), "cancel");
        }

        GuardContext ctx = new GuardContext(request.getMakerId(), 0, actorId, "MAKER", false,
                currentState, transition.requiredApprovals());
        for (String guardName : transition.guards()) {
            if (!guards.get(guardName).evaluate(ctx)) {
                throw new ForbiddenActionException("Only the maker can cancel request " + requestId);
            }
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, workflow, latest.getState(), latest.getVersion(), "cancel");
        }
        writeAudit(requestId, actorId, "MAKER", "CANCELLED", currentState, transition.to());
        fireEvents(workflow, requestId, transition.to());
        return new ApprovalRequestView(requestId, transition.to(), request.getVersion() + 1);
    }

    private void fireEvents(WorkflowDefinition workflow, String requestId, String state) {
        for (String eventType : workflow.eventsFor(state)) {
            writeOutbox(requestId, eventType);
        }
    }

    // Row-locking on purpose (spec §12 amendment): approve/reject/cancel all
    // read-then-maybe-transition based on a decision COUNT, which is an
    // aggregate, not a single-row transition — the guarded UPDATE alone can't
    // protect it. Taking the lock here serializes concurrent commands on the
    // same request, so the second one to run always sees the first's
    // committed decision before deciding whether quorum is met.
    private ApprovalRequest loadOrThrow(String requestId) {
        return requests.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(requestId));
    }

    /**
     * A "lost race" (409 CONCURRENT_STATE_CHANGE) is a `current` state reachable from some
     * state `action` could have started from -- the action was legal when presumably read,
     * just lost the race. Otherwise it's 409 INVALID_STATE_TRANSITION. Reachability is BFS
     * shortest-path over the workflow's own graph, not hardcoded to any one workflow's shape.
     * When a shortcut to `current` bypasses `action`'s stage entirely, currentVersion (hops
     * actually taken on this row) disambiguates: matching the shortcut's length means this
     * row took the shortcut and never saw `action`'s stage (illegal); anything else is a race.
     */
    private RuntimeException classifyRaceOrIllegal(String requestId, WorkflowDefinition workflow, String current,
                                                     long currentVersion, String action) {
        List<String> candidateStarts = workflow.transitions().stream()
                .filter(t -> t.name().equals(action))
                .map(Transition::from)
                .distinct()
                .toList();

        if (candidateStarts.isEmpty()) {
            return new InvalidStateTransitionException(requestId, current, action);
        }

        int shortestViaCandidateStart = Integer.MAX_VALUE;
        boolean reachableViaCandidateStart = false;
        for (String start : candidateStarts) {
            int hopsToStart = shortestPathLength(workflow, workflow.initialState(), start);
            int hopsFromStart = shortestPathLength(workflow, start, current);
            if (hopsToStart >= 0 && hopsFromStart >= 0) {
                reachableViaCandidateStart = true;
                shortestViaCandidateStart = Math.min(shortestViaCandidateStart, hopsToStart + hopsFromStart);
            }
        }

        if (!reachableViaCandidateStart) {
            return new InvalidStateTransitionException(requestId, current, action);
        }

        int shortestFromInitialState = shortestPathLength(workflow, workflow.initialState(), current);

        if (shortestFromInitialState >= 0 && shortestFromInitialState < shortestViaCandidateStart) {
            return currentVersion == shortestFromInitialState
                    ? new InvalidStateTransitionException(requestId, current, action)
                    : new ConcurrentStateChangeException(requestId, current);
        }
        return new ConcurrentStateChangeException(requestId, current);
    }

    private int shortestPathLength(WorkflowDefinition workflow, String from, String to) {
        if (from.equals(to)) {
            return 0;
        }
        Queue<String> frontier = new ArrayDeque<>();
        HashMap<String, Integer> distance = new HashMap<>();
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

    private void writeAudit(String requestId, String actorId, String actorRole, String action,
                             String from, String to) {
        AuditLog log = new AuditLog();
        log.setRequestId(requestId);
        log.setActorId(actorId);
        log.setActorRole(actorRole);
        log.setAction(action);
        log.setPreviousState(from);
        log.setNewState(to);
        log.setCreatedAt(Instant.now());
        audits.save(log);
    }

    private void writeOutbox(String requestId, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setRequestId(requestId);
        event.setEventType(eventType);
        event.setEventVersion(1);
        event.setPayload("{\"requestId\":\"" + requestId + "\",\"eventType\":\"" + eventType + "\"}");
        event.setCreatedAt(Instant.now());
        outbox.save(event);
    }

    private ApprovalRequestView toView(ApprovalRequest r) {
        return new ApprovalRequestView(r.getRequestId(), r.getState(), r.getVersion());
    }

    private String hash(CreateApprovalRequest cmd) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(mapper.writeValueAsBytes(cmd));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 17: Rewrite `ExpirySweeper.java`** (reads each candidate's own frozen `policy_snapshot.workflow()`, never the live registry, for the per-candidate lookup; `workflowRegistry` stays injected only for `allNonTerminalStates()`)

```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.workflow.WorkflowDefinition;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ExpirySweeper {

    private final ApprovalRequestRepository requests;
    private final ExpiryTransitionService transitionService;
    private final WorkflowRegistry workflowRegistry;

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
            WorkflowDefinition workflow = candidate.getPolicySnapshot().workflow();
            if (transitionService.expireOne(candidate.getRequestId(), candidate.getVersion(), workflow, candidate.getState())) {
                expiredCount++;
            }
        }
        return expiredCount;
    }

    // Thin delegator so existing/prior test call sites (sweeper.expireOne(requestId, version))
    // still exercise the real transactional bean rather than a self-invoked no-op.
    public boolean expireOne(String requestId, long expectedVersion) {
        ApprovalRequest request = requests.findByRequestId(requestId)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(requestId));
        WorkflowDefinition workflow = request.getPolicySnapshot().workflow();
        return transitionService.expireOne(requestId, expectedVersion, workflow, request.getState());
    }
}
```

`ExpiryTransitionService.java` needs no change — it already takes `WorkflowDefinition workflow` as a caller-supplied parameter and never touches the registry or `PolicySnapshot` directly.

- [ ] **Step 18: Rewrite `ApprovalCommandServiceCreateTest.java`**

```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class ApprovalCommandServiceCreateTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ApprovalCommandService service;

    @Autowired
    ApprovalRequestRepository requests;

    private CreateApprovalRequest cmd(String requestId, String workflowId) {
        return new CreateApprovalRequest(
                requestId, "TRANSFER_APPROVAL", "maker-1", workflowId, 1, "v1",
                "{\"transferId\":\"" + requestId + "\"}",
                Instant.now().plusSeconds(86400));
    }

    @Test
    void autoReleaseWorkflowAutoApproves() {
        ApprovalRequestView view = service.create(cmd("auto-1", "transfer-auto-release"), UUID.randomUUID().toString());

        assertThat(view.state()).isEqualTo("APPROVED");
    }

    @Test
    void singleCheckerWorkflowGoesToPendingApproval() {
        ApprovalRequestView view = service.create(cmd("pending-1", "transfer-single-checker"), UUID.randomUUID().toString());

        assertThat(view.state()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void replayingSameIdempotencyKeyReturnsSameResultWithoutSecondRequest() {
        String key = UUID.randomUUID().toString();
        // Reuse the exact same command instance: a real replay resends byte-identical
        // request bytes, which is what the hash-based conflict check keys off of.
        CreateApprovalRequest body = cmd("idem-1", "transfer-auto-release");
        ApprovalRequestView first = service.create(body, key);

        ApprovalRequestView second = service.create(body, key);

        assertThat(second.requestId()).isEqualTo(first.requestId());
        assertThat(second.state()).isEqualTo(first.state());
    }

    @Test
    void replayingSameKeyWithDifferentBodyThrowsConflict() {
        String key = UUID.randomUUID().toString();
        service.create(cmd("idem-2", "transfer-auto-release"), key);

        assertThatThrownBy(() -> service.create(cmd("idem-3", "transfer-auto-release"), key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void reusingRequestIdUnderADifferentKeyThrowsConflictRatherThanOverwriting() {
        service.create(cmd("reuse-1", "transfer-single-checker"), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.create(cmd("reuse-1", "transfer-auto-release"), UUID.randomUUID().toString()))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void createResolvesAndPersistsTheSelectedWorkflow() {
        ApprovalRequestView view = service.create(cmd("workflow-resolve-1", "transfer-auto-release"), UUID.randomUUID().toString());

        ApprovalRequest saved = requests.findByRequestId(view.requestId()).orElseThrow();
        assertThat(saved.getWorkflowId()).isEqualTo("transfer-auto-release");
        assertThat(saved.getWorkflowVersion()).isEqualTo(1);
    }

    @Test
    void unknownWorkflowIdRejectedAtCreation() {
        assertThatThrownBy(() -> service.create(cmd("bad-workflow-1", "does-not-exist"), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidRequestException.class);
    }
}
```

- [ ] **Step 19: Rewrite `ApprovalCommandServiceApproveTest.java`**

```java
package com.visionbank.approval.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class ApprovalCommandServiceApproveTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ApprovalCommandService service;

    private String createPending(String requestId, String workflowId) {
        service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
                workflowId, 1, "v1", "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
        return requestId;
    }

    @Test
    void singleApprovalOnSingleCheckerWorkflowTransitionsToApproved() {
        String id = createPending("req-single", "transfer-single-checker");

        ApprovalRequestView view = service.approve(id, "checker-1", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo("APPROVED");
    }

    @Test
    void firstOfTwoRequiredApprovalsRecordsWithoutTransitioning() {
        String id = createPending("req-quorum", "transfer-high-value");

        ApprovalRequestView view = service.approve(id, "checker-1", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void secondOfTwoRequiredApprovalsTransitionsToApproved() {
        String id = createPending("req-quorum-2", "transfer-high-value");
        service.approve(id, "checker-1", "TRANSFER_CHECKER");

        ApprovalRequestView view = service.approve(id, "checker-2", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo("APPROVED");
    }

    @Test
    void makerCannotApproveOwnRequest() {
        String id = createPending("req-maker", "transfer-single-checker");

        assertThatThrownBy(() -> service.approve(id, "maker-1", "TRANSFER_CHECKER"))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void ineligibleRoleCannotApprove() {
        String id = createPending("req-role", "transfer-single-checker");

        assertThatThrownBy(() -> service.approve(id, "auditor-1", "AUDITOR"))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void approvingAlreadyTerminalRequestThrowsConcurrentStateChange() {
        String id = createPending("req-terminal", "transfer-single-checker");
        service.cancel(id, "maker-1");

        assertThatThrownBy(() -> service.approve(id, "checker-1", "TRANSFER_CHECKER"))
                .isInstanceOf(ConcurrentStateChangeException.class);
    }

    @Test
    void approvingAutoApprovedRequestThrowsInvalidStateTransition() {
        String id = createPending("req-auto", "transfer-auto-release");

        assertThatThrownBy(() -> service.approve(id, "checker-1", "TRANSFER_CHECKER"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectTransitionsPendingToRejected() {
        String id = createPending("req-reject", "transfer-single-checker");

        ApprovalRequestView view = service.reject(id, "checker-1", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo("REJECTED");
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        String id = createPending("req-cancel", "transfer-single-checker");

        ApprovalRequestView view = service.cancel(id, "maker-1");

        assertThat(view.state()).isEqualTo("CANCELLED");
    }
}
```

- [ ] **Step 20: Rewrite `PrivilegedAccessWorkflowTest.java`** (drop `createRejectsAnIncompleteStagePolicyMapInsteadOfWedgingLater` — its premise, a caller-supplied `stagePolicies` map validated for completeness, no longer exists: `PolicySnapshot` is always complete by construction now, built server-side from the resolved workflow; replace the custom-2-required-approvals variant with `privileged-access:v2`)

```java
package com.visionbank.approval.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
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
        return new CreateApprovalRequest(requestId, "PRIVILEGED_ACCESS", "maker-1",
                "privileged-access", 1, "v1", "{\"resource\":\"prod-db\"}", Instant.now().plusSeconds(86400));
    }

    private CreateApprovalRequest cmdV2(String requestId) {
        return new CreateApprovalRequest(requestId, "PRIVILEGED_ACCESS", "maker-1",
                "privileged-access", 2, "v1", "{\"resource\":\"prod-db\"}", Instant.now().plusSeconds(86400));
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

        // "sec-1" retrying with the SECURITY role after the row already moved on to
        // MANAGER_APPROVAL is ineligible there -- this exercises the fallback (an actor
        // who has decided ANYWHERE on the request gets a harmless no-op instead of a hard
        // Forbidden), not the state-scoped idempotency check itself (that's the next test).
        service.approve(created.requestId(), "sec-1", "SECURITY");
        ApprovalRequestView replay = service.approve(created.requestId(), "sec-1", "SECURITY");
        assertThat(replay.state()).isEqualTo("MANAGER_APPROVAL"); // already moved on

        // the SAME actor id acting again at a later stage (different role) is a genuinely
        // new decision -- proves the (request_id, actor_id, state) constraint works.
        ApprovalRequestView afterManager = service.approve(created.requestId(), "sec-1", "MANAGER");
        assertThat(afterManager.state()).isEqualTo("COMPLIANCE_REVIEW");
    }

    @Test
    void sameActorApprovingTheSameStageTwiceWhileItsStillCurrentIsAGenuineIdempotentReplay() {
        // privileged-access:v2 requires 2 SECURITY approvals (v1 requires only 1) --
        // exercises both the idempotent-replay-under-unmet-quorum behavior AND that the
        // versioned registry genuinely resolves a DIFFERENT definition for the same
        // workflowId, per the design's registry-versioning requirement.
        String id = service.create(cmdV2("priv-idem-1"), UUID.randomUUID().toString()).requestId();

        ApprovalRequestView first = service.approve(id, "sec-1", "SECURITY");
        assertThat(first.state()).isEqualTo("SECURITY_REVIEW"); // quorum not yet met (1 of 2)

        ApprovalRequestView replay = service.approve(id, "sec-1", "SECURITY");
        assertThat(replay.state()).isEqualTo("SECURITY_REVIEW"); // still not met -- the SAME decision replayed, not counted twice
    }
}
```

- [ ] **Step 21: Update `PrivilegedAccessConcurrencyTest.java`'s `create()` helper** — replace:
```java
private String create(String requestId) {
    Map<String, StagePolicy> stages = Map.of(
            "SECURITY_REVIEW", new StagePolicy(1, List.of("SECURITY")),
            "MANAGER_APPROVAL", new StagePolicy(1, List.of("MANAGER")),
            "COMPLIANCE_REVIEW", new StagePolicy(1, List.of("COMPLIANCE")));
    CreateApprovalRequest cmd = new CreateApprovalRequest(requestId, "PRIVILEGED_ACCESS", "maker-1",
            new PolicySnapshot("v1", stages, false), "{}", Instant.now().plusSeconds(86400));
    return service.create(cmd, UUID.randomUUID().toString()).requestId();
}
```
with:
```java
private String create(String requestId) {
    CreateApprovalRequest cmd = new CreateApprovalRequest(requestId, "PRIVILEGED_ACCESS", "maker-1",
            "privileged-access", 1, "v1", "{}", Instant.now().plusSeconds(86400));
    return service.create(cmd, UUID.randomUUID().toString()).requestId();
}
```
and remove the now-unused `com.visionbank.approval.domain.PolicySnapshot`, `com.visionbank.approval.domain.StagePolicy`, `java.util.List`, `java.util.Map` imports. The rest of the file (all three `@Test` methods) is unchanged.

- [ ] **Step 22: Update `ApprovalConcurrencyTest.java`'s two `createPending*` helpers** — replace:
```java
private String createPendingRequiredOne(String requestId) {
    service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
            new PolicySnapshot("v1", Map.of("PENDING_APPROVAL", new StagePolicy(1, List.of("TRANSFER_CHECKER"))), false),
            "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
    return requestId;
}

private String createPendingRequiredTwo(String requestId) {
    service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
            new PolicySnapshot("v1", Map.of("PENDING_APPROVAL", new StagePolicy(2, List.of("TRANSFER_CHECKER"))), false),
            "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
    return requestId;
}
```
with:
```java
private String createPendingRequiredOne(String requestId) {
    service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
            "transfer-single-checker", 1, "v1", "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
    return requestId;
}

private String createPendingRequiredTwo(String requestId) {
    service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
            "transfer-high-value", 1, "v1", "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
    return requestId;
}
```
and remove the now-unused `com.visionbank.approval.domain.PolicySnapshot`, `com.visionbank.approval.domain.StagePolicy`, `java.util.List`, `java.util.Map` imports. All three `@Test` methods (`twoCheckersSatisfyingQuorumSimultaneously...`, `twoCheckersApprovingSimultaneously_exactlyOneWins`, `cancelVersusApprove_exactlyOneWins`) and the `raceTask`/`resolve` helpers are unchanged.

- [ ] **Step 23: Rewrite `ExpiryVersusApproveConcurrencyTest.java`** (builds a raw `ApprovalRequest` directly, bypassing `service.create()` — needs `WorkflowRegistry` autowired to embed a real fixture `WorkflowDefinition` into `PolicySnapshot`)

```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ExpiryVersusApproveConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalRequestRepository requests;
    @Autowired ApprovalCommandService service;
    @Autowired ExpirySweeper sweeper;
    @Autowired WorkflowRegistry workflowRegistry;

    @Test
    void approveVersusExpire_exactlyOneWins() throws Exception {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId("race-expire");
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState("PENDING_APPROVAL");
        r.setVersion(1L);
        r.setMakerId("maker-1");
        r.setPolicySnapshot(new PolicySnapshot("v1", workflowRegistry.get("transfer-single-checker", 1)));
        r.setPayload("{}");
        r.setCreatedAt(Instant.now().minusSeconds(90000));
        r.setExpiresAt(Instant.now().minusSeconds(1));
        r.setWorkflowId("transfer-single-checker");
        r.setWorkflowVersion(1);
        requests.save(r);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Boolean> expireResult = pool.submit(() -> {
            startGate.await();
            return sweeper.expireOne("race-expire", 1L);
        });
        Future<Object> approveResult = pool.submit(() -> {
            startGate.await();
            try {
                return service.approve("race-expire", "checker-A", "TRANSFER_CHECKER");
            } catch (ConcurrentStateChangeException e) {
                return e;
            }
        });
        startGate.countDown();

        boolean expired = expireResult.get(10, TimeUnit.SECONDS);
        Object approveOutcome = approveResult.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        boolean approveWon = approveOutcome instanceof ApprovalRequestView;
        assertThat(expired ^ approveWon).isTrue(); // exactly one of the two won

        String finalState = requests.findByRequestId("race-expire").get().getState();
        assertThat(finalState).isIn("EXPIRED", "APPROVED");
    }
}
```

- [ ] **Step 24: Rewrite `ExpirySweeperTest.java`** (same pattern — autowire `WorkflowRegistry` for the raw-entity fixture)

```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ExpirySweeperTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ExpirySweeper sweeper;
    @Autowired ApprovalRequestRepository requests;
    @Autowired WorkflowRegistry workflowRegistry;

    private ApprovalRequest stalePendingRequest(String id) {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId(id);
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState("PENDING_APPROVAL");
        r.setVersion(1L);
        r.setMakerId("maker-1");
        r.setPolicySnapshot(new PolicySnapshot("v1", workflowRegistry.get("transfer-single-checker", 1)));
        r.setPayload("{}");
        r.setCreatedAt(Instant.now().minusSeconds(90000));
        r.setExpiresAt(Instant.now().minusSeconds(3600));
        r.setWorkflowId("transfer-single-checker");
        r.setWorkflowVersion(1);
        return requests.save(r);
    }

    @Test
    void sweeperExpiresStalePendingRequest() {
        stalePendingRequest("expire-1");

        int expired = sweeper.sweepOnce();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(requests.findByRequestId("expire-1").get().getState()).isEqualTo("EXPIRED");
    }

    @Test
    void sweeperIgnoresRequestsNotYetExpired() {
        ApprovalRequest fresh = stalePendingRequest("expire-2");
        fresh.setExpiresAt(Instant.now().plusSeconds(3600));
        requests.save(fresh);

        sweeper.sweepOnce();

        assertThat(requests.findByRequestId("expire-2").get().getState()).isEqualTo("PENDING_APPROVAL");
    }
}
```

- [ ] **Step 25: Rewrite `ApprovalRequestRepositoryTest.java`** (`@DataJpaTest` doesn't load `WorkflowConfig`/`WorkflowRegistry`, and this test only exercises the raw `guardedTransition` SQL, not any guard/role logic — build a minimal, self-contained `WorkflowDefinition` literal instead of depending on a real YAML fixture)

```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class ApprovalRequestRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ApprovalRequestRepository repository;

    private static final WorkflowDefinition TEST_WORKFLOW = new WorkflowDefinition(
            "repo-test-workflow", 1,
            List.of(new WorkflowDefinition.StateDef("PENDING_APPROVAL", "Pending Approval"),
                    new WorkflowDefinition.StateDef("APPROVED", "Approved"),
                    new WorkflowDefinition.StateDef("EXPIRED", "Expired")),
            "PENDING_APPROVAL",
            Set.of("APPROVED", "EXPIRED"),
            List.of(new Transition("approve", "PENDING_APPROVAL", "APPROVED",
                            List.of("approvals_satisfied"), List.of("TRANSFER_CHECKER"), 2),
                    new Transition("expire", "PENDING_APPROVAL", "EXPIRED", List.of("sla_expired"), List.of(), null)),
            Map.of());

    private ApprovalRequest newRequest(String id) {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId(id);
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState("PENDING_APPROVAL");
        r.setVersion(0L);
        r.setMakerId("maker-1");
        r.setPolicySnapshot(new PolicySnapshot("v1", TEST_WORKFLOW));
        r.setPayload("{}");
        r.setCreatedAt(Instant.now());
        r.setExpiresAt(Instant.now().plusSeconds(86400));
        r.setWorkflowId("repo-test-workflow");
        r.setWorkflowVersion(1);
        return r;
    }

    @Test
    void guardedTransitionSucceedsWhenStateAndVersionMatch() {
        repository.saveAndFlush(newRequest("req-1"));

        int rows = repository.guardedTransition("req-1", "PENDING_APPROVAL", 0L, "APPROVED");

        assertThat(rows).isEqualTo(1);
        Optional<ApprovalRequest> reloaded = repository.findByRequestId("req-1");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getState()).isEqualTo("APPROVED");
        assertThat(reloaded.get().getVersion()).isEqualTo(1L);
    }

    @Test
    void guardedTransitionFailsWhenVersionStale() {
        repository.saveAndFlush(newRequest("req-2"));

        int rows = repository.guardedTransition("req-2", "PENDING_APPROVAL", 5L, "APPROVED");

        assertThat(rows).isEqualTo(0);
        assertThat(repository.findByRequestId("req-2").get().getState()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void guardedTransitionFailsWhenStateStale() {
        repository.saveAndFlush(newRequest("req-3"));

        int rows = repository.guardedTransition("req-3", "APPROVED", 0L, "EXPIRED");

        assertThat(rows).isEqualTo(0);
    }
}
```

- [ ] **Step 26: Run the full `approval-engine` test suite**

```bash
cd approval-engine && ./gradlew test
```
Expected: all tests pass — this is the first point in the task where the module compiles at all, given the coupling explained at the top of this task. If anything fails, fix it before proceeding; do not partially commit.

- [ ] **Step 27: Commit**

```bash
git add approval-engine/
git commit -m "$(cat <<'EOF'
feat: version-keyed WorkflowRegistry, roles/quorum on transitions, PolicySnapshot embeds WorkflowDefinition

WorkflowRegistry is now keyed by (workflowId, workflowVersion). Every
request freezes its resolved WorkflowDefinition into policy_snapshot
at creation time, and every later operation reads that frozen copy --
never the live registry again, so an existing request keeps working
correctly even if its originating YAML file is later changed or
removed.

allowedRoles/requiredApprovals move from caller-supplied JSON into
the workflow YAML's transitions, checked intrinsically by the engine
rather than via an opt-in guard. WorkflowSelector's requestType-based
routing is removed -- callers supply the exact workflowId/version
directly. transfer-approval splits into three fixed-quorum workflows
(transfer-auto-release/transfer-single-checker/transfer-high-value)
since quorum is now fixed per workflow version, not caller-supplied.
makerCanApprove is replaced by a declarative actor_is_not_maker guard.

See docs/superpowers/specs/2026-08-26-workflow-versioning-and-role-authority-design.md.
EOF
)"
```

---

## Task 2: `ApprovalController`, DTOs, and `availableActions`

**Files:**
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/ApprovalController.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/dto/CreateApprovalRequestDto.java`
- Delete: `approval-engine/src/main/java/com/visionbank/approval/web/dto/StagePolicyDto.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/web/dto/WorkflowViewDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/AvailableActionDto.java`
- Modify/Test: `approval-engine/src/test/java/com/visionbank/approval/web/ApprovalControllerTest.java`

**Interfaces:**
- Consumes: `CreateApprovalRequest`, `Transition.allowedRoles()/.requiredApprovals()`, `ApprovalCommandService.create()/approve()/reject()/cancel()` (Task 1).
- Produces: `POST /approvals` request shape (`workflowId`, `workflowVersion`, `policyVersion` instead of `stagePolicies`/`makerCanApprove`); `GET /approvals/{id}/workflow-view` response gains `availableActions` — Task 3's banking-service `ApprovalEngineClient`/UI mirror this shape.

- [ ] **Step 1: Rewrite `CreateApprovalRequestDto.java`; delete `StagePolicyDto.java`**

```java
package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateApprovalRequestDto(
        @NotBlank String requestId,
        @NotBlank String requestType,
        @NotBlank String makerId,
        @NotBlank String workflowId,
        @Min(1) int workflowVersion,
        @NotBlank String policyVersion,
        @NotBlank String payloadJson,
        @NotNull Instant expiresAt) {}
```

```bash
rm approval-engine/src/main/java/com/visionbank/approval/web/dto/StagePolicyDto.java
```

- [ ] **Step 2: Create `AvailableActionDto.java`; add `availableActions` to `WorkflowViewDto.java`**

```java
package com.visionbank.approval.web.dto;

import java.util.List;

public record AvailableActionDto(String name, List<String> allowedRoles,
                                  Integer requiredApprovals, Integer currentApprovals) {}
```

```java
package com.visionbank.approval.web.dto;

import java.util.List;

public record WorkflowViewDto(String workflowId, int workflowVersion, String currentState,
                               List<String> terminalStates, List<StageViewDto> stages,
                               List<AvailableActionDto> availableActions) {}
```

- [ ] **Step 3: Rewrite `ApprovalController.java`** (drop `WorkflowRegistry` dependency entirely — `workflow-view` reads `request.getPolicySnapshot().workflow()`; `create()` no longer builds `PolicySnapshot` itself, just maps the DTO into the command; `buildStageView` finds the approve-transition FROM each state instead of a stage-keyed policy map; add `availableActions`)

```java
package com.visionbank.approval.web;

import com.visionbank.approval.domain.ApprovalDecision;
import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.AuditLog;
import com.visionbank.approval.repository.ApprovalDecisionRepository;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.repository.AuditLogRepository;
import com.visionbank.approval.service.*;
import com.visionbank.approval.web.dto.*;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalCommandService service;
    private final ApprovalRequestRepository requests;
    private final AuditLogRepository audits;
    private final ApprovalDecisionRepository decisions;

    public ApprovalController(ApprovalCommandService service, ApprovalRequestRepository requests,
                               AuditLogRepository audits, ApprovalDecisionRepository decisions) {
        this.service = service;
        this.requests = requests;
        this.audits = audits;
        this.decisions = decisions;
    }

    @PostMapping
    public ApprovalResponseDto create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody CreateApprovalRequestDto dto) {
        CreateApprovalRequest cmd = new CreateApprovalRequest(dto.requestId(), dto.requestType(), dto.makerId(),
                dto.workflowId(), dto.workflowVersion(), dto.policyVersion(), dto.payloadJson(), dto.expiresAt());
        ApprovalRequestView view = service.create(cmd, idempotencyKey);
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/approve")
    public ApprovalResponseDto approve(@PathVariable String id, @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.approve(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponseDto reject(@PathVariable String id, @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.reject(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/cancel")
    public ApprovalResponseDto cancel(@PathVariable String id, @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.cancel(id, dto.actorId());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @GetMapping("/{id}")
    public ApprovalResponseDto get(@PathVariable String id) {
        var request = requests.findByRequestId(id)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        return new ApprovalResponseDto(request.getRequestId(), request.getState(), request.getVersion());
    }

    @GetMapping("/{id}/audit")
    public List<AuditLogEntryDto> audit(@PathVariable String id) {
        return audits.findByRequestIdOrderByCreatedAtAsc(id).stream()
                .map(a -> new AuditLogEntryDto(a.getAction(), a.getPreviousState(), a.getNewState(),
                        a.getActorId(), a.getActorRole(), a.getCreatedAt()))
                .toList();
    }

    @GetMapping("/{id}/workflow-view")
    public WorkflowViewDto workflowView(@PathVariable String id) {
        ApprovalRequest request = requests.findByRequestId(id)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        WorkflowDefinition workflow = request.getPolicySnapshot().workflow();
        String currentState = request.getState();
        List<AuditLog> auditLog = audits.findByRequestIdOrderByCreatedAtAsc(id);

        List<StageViewDto> stages = workflow.states().stream()
                .map(s -> buildStageView(workflow, request, s, currentState, auditLog))
                .toList();

        List<AvailableActionDto> availableActions = workflow.transitionsFrom(currentState).stream()
                .map(t -> new AvailableActionDto(t.name(), t.allowedRoles(), t.requiredApprovals(),
                        t.requiredApprovals() == null ? null
                                : (int) decisions.countByRequestIdAndDecisionAndState(
                                        request.getRequestId(), ApprovalDecision.DecisionType.APPROVE, currentState)))
                .toList();

        return new WorkflowViewDto(workflow.name(), workflow.version(), currentState,
                new java.util.ArrayList<>(workflow.terminalStates()), stages, availableActions);
    }

    private StageViewDto buildStageView(WorkflowDefinition workflow, ApprovalRequest request,
                                         WorkflowDefinition.StateDef stateDef, String currentState,
                                         List<AuditLog> auditLog) {
        String id = stateDef.id();
        String status;
        if (id.equals(currentState) && workflow.isTerminal(id)) {
            status = isSuccessTerminal(workflow, id) ? "COMPLETED" : "FAILED";
        } else if (id.equals(currentState)) {
            status = "IN_PROGRESS";
        } else {
            status = hasEverReached(auditLog, id) ? "COMPLETED" : "PENDING";
        }

        Transition approveFromHere = workflow.transitionsFrom(id).stream()
                .filter(t -> t.name().equals("approve") && t.requiredApprovals() != null)
                .findFirst()
                .orElse(null);
        if (approveFromHere == null) {
            return new StageViewDto(id, stateDef.label(), status, null, null, List.of());
        }

        List<DecisionViewDto> approvals = decisions.findByRequestIdAndState(request.getRequestId(), id).stream()
                .map(d -> new DecisionViewDto(d.getActorId(), d.getActorRole(), d.getDecision().name(), d.getCreatedAt()))
                .toList();
        long completed = approvals.stream().filter(a -> a.decision().equals("APPROVE")).count();

        return new StageViewDto(id, stateDef.label(), status, approveFromHere.requiredApprovals(), (int) completed, approvals);
    }

    private boolean hasEverReached(List<AuditLog> auditLog, String stateId) {
        return auditLog.stream().anyMatch(a -> a.getNewState().equals(stateId) || a.getPreviousState().equals(stateId));
    }

    private boolean isSuccessTerminal(WorkflowDefinition workflow, String state) {
        return workflow.transitions().stream()
                .anyMatch(t -> t.to().equals(state) && t.name().toLowerCase().contains("approve"));
    }
}
```

- [ ] **Step 4: Rewrite `ApprovalControllerTest.java`**

```java
package com.visionbank.approval.web;

import tools.jackson.databind.ObjectMapper;
import com.visionbank.approval.web.dto.CreateApprovalRequestDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    private String createDto(String requestId, String workflowId) throws Exception {
        return mapper.writeValueAsString(new CreateApprovalRequestDto(
                requestId, "TRANSFER_APPROVAL", "maker-1", workflowId, 1, "v1", "{}", Instant.now().plusSeconds(86400)));
    }

    @Test
    void createReturns200WithPendingApprovalState() throws Exception {
        mockMvc.perform(post("/approvals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(createDto("ctrl-1", "transfer-single-checker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PENDING_APPROVAL")));
    }

    @Test
    void approveOnAlreadyApprovedRequestReturns409WithConcurrentStateChangeCode() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-2", "transfer-single-checker")));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                .contentType("application/json")
                .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-1", "TRANSFER_CHECKER"))));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-2", "TRANSFER_CHECKER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_STATE_CHANGE")));
    }

    @Test
    void workflowViewShowsStageProgressForAPendingRequest() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-4", "transfer-high-value")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/approvals/ctrl-4/workflow-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState", is("PENDING_APPROVAL")))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].status", is(List.of("IN_PROGRESS"))))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].requiredApprovals", is(List.of(2))))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].completedApprovals", is(List.of(0))))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].approvals[0]").doesNotExist())
                .andExpect(jsonPath("$.stages[?(@.id=='SUBMITTED')].status", is(List.of("COMPLETED"))))
                .andExpect(jsonPath("$.availableActions[?(@.name=='approve')].allowedRoles", is(List.of(List.of("TRANSFER_CHECKER")))))
                .andExpect(jsonPath("$.availableActions[?(@.name=='cancel')].name", is(List.of("cancel"))));
    }

    @Test
    void workflowViewShowsFailedStatusForARejectedRequest() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-5", "transfer-single-checker")));

        mockMvc.perform(post("/approvals/ctrl-5/reject")
                .contentType("application/json")
                .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-1", "TRANSFER_CHECKER"))));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/approvals/ctrl-5/workflow-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState", is("REJECTED")))
                .andExpect(jsonPath("$.stages[?(@.id=='REJECTED')].status", is(List.of("FAILED"))))
                .andExpect(jsonPath("$.stages[?(@.id=='APPROVED')].status", is(List.of("PENDING"))));
    }

    @Test
    void getReturnsCurrentStateAndReturns404WhenNotFound() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-3", "transfer-single-checker")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals/ctrl-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PENDING_APPROVAL")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 5: Run the full `approval-engine` test suite and commit**

```bash
cd approval-engine && ./gradlew test
git add approval-engine/
git commit -m "$(cat <<'EOF'
feat: ApprovalController reads policy_snapshot.workflow, exposes availableActions

Create no longer builds PolicySnapshot from caller-supplied stagePolicies;
it just forwards workflowId/workflowVersion/policyVersion to the command
service, which resolves and freezes the workflow itself. workflow-view
adds availableActions (name/allowedRoles/requiredApprovals/currentApprovals
per reachable transition) so a UI can render action buttons without
hardcoded knowledge of the workflow shape -- advisory only, per spec §7;
the command endpoints remain the actual authorization boundary.
EOF
)"
```

---

## Task 3: `banking-service` — `PolicyResolver` selects a workflow, not a policy

**Files:**
- Delete: `banking-service/src/main/java/com/visionbank/banking/policy/ApprovalPolicy.java`
- Create: `banking-service/src/main/java/com/visionbank/banking/policy/WorkflowSelection.java`
- Modify: `banking-service/src/main/java/com/visionbank/banking/policy/PolicyResolver.java`
- Modify: `banking-service/src/main/java/com/visionbank/banking/approval/CreateWorkflowRequest.java`
- Modify: `banking-service/src/main/java/com/visionbank/banking/approval/ApprovalEngineClient.java`
- Modify: `banking-service/src/main/java/com/visionbank/banking/service/TransferSubmissionService.java`
- Modify/Test: `banking-service/src/test/java/com/visionbank/banking/policy/PolicyResolverTest.java`
- Modify/Test: `banking-service/src/test/java/com/visionbank/banking/approval/ApprovalEngineClientTest.java`

**Interfaces:**
- Consumes: approval-engine's `POST /approvals` request shape from Task 2 (`workflowId`, `workflowVersion`, `policyVersion` fields, no `stagePolicies`/`makerCanApprove`).
- Produces: `WorkflowSelection(String workflowId, int workflowVersion)`, `PolicyResolver.resolve(long amountMinorUnits) -> WorkflowSelection` — nothing downstream of this task depends on these beyond `TransferSubmissionService` (already covered in this same task).

- [ ] **Step 1: Delete `ApprovalPolicy.java`; create `WorkflowSelection.java`**

```bash
rm banking-service/src/main/java/com/visionbank/banking/policy/ApprovalPolicy.java
```

```java
package com.visionbank.banking.policy;

public record WorkflowSelection(String workflowId, int workflowVersion) {}
```

- [ ] **Step 2: Rewrite `PolicyResolver.java`** (returns a `WorkflowSelection` chosen by amount tier — the three tiers now map to the three fixed-quorum workflows from Task 1's spec §3.5, not a caller-tunable `requiredApprovals` value)

```java
package com.visionbank.banking.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PolicyResolver {

    private final long autoReleaseCeiling;
    private final long singleCheckerCeiling;

    public PolicyResolver(@Value("${policy.auto-release-ceiling-minor-units}") long autoReleaseCeiling,
                           @Value("${policy.single-checker-ceiling-minor-units}") long singleCheckerCeiling) {
        this.autoReleaseCeiling = autoReleaseCeiling;
        this.singleCheckerCeiling = singleCheckerCeiling;
    }

    public WorkflowSelection resolve(long amountMinorUnits) {
        if (amountMinorUnits < autoReleaseCeiling) {
            return new WorkflowSelection("transfer-auto-release", 1);
        }
        if (amountMinorUnits < singleCheckerCeiling) {
            return new WorkflowSelection("transfer-single-checker", 1);
        }
        return new WorkflowSelection("transfer-high-value", 1);
    }
}
```

- [ ] **Step 3: Rewrite `CreateWorkflowRequest.java`**

```java
package com.visionbank.banking.approval;

import com.visionbank.banking.policy.WorkflowSelection;

import java.time.Instant;

public record CreateWorkflowRequest(
        String requestId, String requestType, String makerId,
        WorkflowSelection workflow, String payloadJson, Instant expiresAt) {}
```

- [ ] **Step 4: Update `ApprovalEngineClient.java`'s `createWorkflow()`** — replace:
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
with:
```java
public WorkflowResponse createWorkflow(CreateWorkflowRequest req, String idempotencyKey) {
    Map<String, Object> body = Map.of(
            "requestId", req.requestId(),
            "requestType", req.requestType(),
            "makerId", req.makerId(),
            "workflowId", req.workflow().workflowId(),
            "workflowVersion", req.workflow().workflowVersion(),
            "policyVersion", "v1",
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
The rest of the file (`getApproval`/`getAuditLog`/`decide`/`getWorkflowView`, the constructor) is unchanged.

- [ ] **Step 5: Update `TransferSubmissionService.java`'s `completeWorkflowCreation()`** — replace:
```java
private TransferView completeWorkflowCreation(Transfer transfer, SubmitTransferCommand cmd) {
    ApprovalPolicy policy = policyResolver.resolve(cmd.amountMinorUnits());
    CreateWorkflowRequest workflowRequest = new CreateWorkflowRequest(
            transfer.getTransferId(), "TRANSFER_APPROVAL", cmd.makerId(), policy,
            "{\"transferId\":\"" + transfer.getTransferId() + "\",\"amount\":" + cmd.amountMinorUnits() + "}",
            transfer.getExpiresAt());
    WorkflowResponse workflowResponse = approvalEngineClient.createWorkflow(workflowRequest, transfer.getTransferId());

    Transfer completed = persistenceService.markWaitingForApproval(transfer.getTransferId(), workflowResponse.requestId());
    return new TransferView(completed.getTransferId(), completed.getState());
}
```
with:
```java
private TransferView completeWorkflowCreation(Transfer transfer, SubmitTransferCommand cmd) {
    WorkflowSelection selection = policyResolver.resolve(cmd.amountMinorUnits());
    CreateWorkflowRequest workflowRequest = new CreateWorkflowRequest(
            transfer.getTransferId(), "TRANSFER_APPROVAL", cmd.makerId(), selection,
            "{\"transferId\":\"" + transfer.getTransferId() + "\",\"amount\":" + cmd.amountMinorUnits() + "}",
            transfer.getExpiresAt()); // persisted value — never recomputed on retry
    WorkflowResponse workflowResponse = approvalEngineClient.createWorkflow(workflowRequest, transfer.getTransferId());

    // Always WAITING_FOR_APPROVAL here regardless of workflowResponse.state() —
    // release is only ever triggered by consuming ApprovalApproved, so auto-release
    // and N-approver release share one trigger path.
    Transfer completed = persistenceService.markWaitingForApproval(transfer.getTransferId(), workflowResponse.requestId());
    return new TransferView(completed.getTransferId(), completed.getState());
}
```
and change the import `com.visionbank.banking.policy.ApprovalPolicy` to `com.visionbank.banking.policy.WorkflowSelection`. No other part of the file changes.

- [ ] **Step 6: Rewrite `PolicyResolverTest.java`** (drops `makerCanNeverApproveUnderThisPolicy` — self-approval is now the workflow YAML's concern, not `PolicyResolver`'s)

```java
package com.visionbank.banking.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyResolverTest {

    private final PolicyResolver resolver = new PolicyResolver(500000L, 5000000L);

    @Test
    void belowAutoReleaseCeilingSelectsAutoReleaseWorkflow() {
        WorkflowSelection selection = resolver.resolve(100000L);
        assertThat(selection.workflowId()).isEqualTo("transfer-auto-release");
        assertThat(selection.workflowVersion()).isEqualTo(1);
    }

    @Test
    void betweenCeilingsSelectsSingleCheckerWorkflow() {
        WorkflowSelection selection = resolver.resolve(1000000L);
        assertThat(selection.workflowId()).isEqualTo("transfer-single-checker");
    }

    @Test
    void atOrAboveSingleCheckerCeilingSelectsHighValueWorkflow() {
        WorkflowSelection selection = resolver.resolve(5000000L);
        assertThat(selection.workflowId()).isEqualTo("transfer-high-value");
    }
}
```

- [ ] **Step 7: Update `ApprovalEngineClientTest.java`'s `createWorkflowPostsToApprovalsAndParsesResponse`** — replace:
```java
@Test
void createWorkflowPostsToApprovalsAndParsesResponse() {
    wireMock.stubFor(post(urlEqualTo("/approvals"))
            .willReturn(okJson("{\"requestId\":\"req-1\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

    CreateWorkflowRequest req = new CreateWorkflowRequest("req-1", "TRANSFER_APPROVAL", "maker-1",
            new ApprovalPolicy(1, List.of("TRANSFER_CHECKER"), false), "{}", Instant.now().plusSeconds(86400));

    WorkflowResponse response = client.createWorkflow(req, UUID.randomUUID().toString());

    assertThat(response.requestId()).isEqualTo("req-1");
    assertThat(response.state()).isEqualTo("PENDING_APPROVAL");
    wireMock.verify(postRequestedFor(urlEqualTo("/approvals"))
            .withHeader("Idempotency-Key", matching(".+"))
            .withRequestBody(matchingJsonPath("$.stagePolicies.PENDING_APPROVAL.requiredApprovals", equalTo("1")))
            .withRequestBody(matchingJsonPath("$.stagePolicies.PENDING_APPROVAL.eligibleRoles[0]", equalTo("TRANSFER_CHECKER"))));
}
```
with:
```java
@Test
void createWorkflowPostsToApprovalsAndParsesResponse() {
    wireMock.stubFor(post(urlEqualTo("/approvals"))
            .willReturn(okJson("{\"requestId\":\"req-1\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

    CreateWorkflowRequest req = new CreateWorkflowRequest("req-1", "TRANSFER_APPROVAL", "maker-1",
            new WorkflowSelection("transfer-single-checker", 1), "{}", Instant.now().plusSeconds(86400));

    WorkflowResponse response = client.createWorkflow(req, UUID.randomUUID().toString());

    assertThat(response.requestId()).isEqualTo("req-1");
    assertThat(response.state()).isEqualTo("PENDING_APPROVAL");
    wireMock.verify(postRequestedFor(urlEqualTo("/approvals"))
            .withHeader("Idempotency-Key", matching(".+"))
            .withRequestBody(matchingJsonPath("$.workflowId", equalTo("transfer-single-checker")))
            .withRequestBody(matchingJsonPath("$.workflowVersion", equalTo("1"))));
}
```
and change the import `com.visionbank.banking.policy.ApprovalPolicy` to `com.visionbank.banking.policy.WorkflowSelection`; remove the now-unused `java.util.List` import (the other test in this file, `getWorkflowViewParsesGenericStagesShape`, doesn't use `List` either). `TransferSubmissionServiceTest.java` and `application.yml` need no changes — run the former as a regression check.

- [ ] **Step 8: Run the `banking-service` test suite and commit**

```bash
cd banking-service && ./gradlew test
git add banking-service/
git commit -m "$(cat <<'EOF'
feat: PolicyResolver selects a workflow, not a caller-tunable policy

Matches approval-engine's new create contract (workflowId/workflowVersion
instead of stagePolicies/makerCanApprove). The three amount tiers now
select transfer-auto-release/transfer-single-checker/transfer-high-value
by workflowId+version instead of sending a requiredApprovals count.
EOF
)"
```

---

## Task 4: UI — `availableActions`-driven action buttons

**Files:**
- Modify: `banking-service/src/main/java/com/visionbank/banking/ui/WorkflowViewDto.java`
- Create: `banking-service/src/main/java/com/visionbank/banking/ui/AvailableActionDto.java`
- Modify/Test: `banking-service/src/test/java/com/visionbank/banking/approval/ApprovalEngineClientTest.java` (the `getWorkflowViewParsesGenericStagesShape` test)
- Modify: `banking-service/src/main/resources/static/ui.html`

**Interfaces:**
- Consumes: `GET /approvals/{id}/workflow-view`'s `availableActions` field from Task 2, proxied unmodified through `banking-service`'s `UiController`/`ApprovalEngineClient.getWorkflowView()` (neither needs code changes — they already pass the whole JSON body through/deserialize generically).
- Produces: nothing consumed by a later task — this is the last task.

- [ ] **Step 1: Add `AvailableActionDto.java`; add `availableActions` to `ui.WorkflowViewDto.java`**

```java
package com.visionbank.banking.ui;

import java.util.List;

public record AvailableActionDto(String name, List<String> allowedRoles,
                                  Integer requiredApprovals, Integer currentApprovals) {}
```

```java
package com.visionbank.banking.ui;

import java.util.List;

public record WorkflowViewDto(String workflowId, int workflowVersion, String currentState,
                               List<String> terminalStates, List<StageViewDto> stages,
                               List<AvailableActionDto> availableActions) {}
```

`UiController.java` needs no change — `getWorkflowView()` just returns whatever `ApprovalEngineClient.getWorkflowView()` deserializes, and the SSE polling loop's `workflow-view` event forwards the same object; neither touches `availableActions` by name.

- [ ] **Step 2: Update `ApprovalEngineClientTest.java`'s `getWorkflowViewParsesGenericStagesShape`** — add `availableActions` to the stub JSON and assert it deserializes. Replace the stub body:
```java
wireMock.stubFor(get(urlEqualTo("/approvals/req-1/workflow-view"))
        .willReturn(okJson("{"
                + "\"workflowId\":\"privileged-access\",\"workflowVersion\":1,\"currentState\":\"MANAGER_APPROVAL\","
                + "\"terminalStates\":[\"APPROVED\",\"REJECTED\",\"EXPIRED\"],"
                + "\"stages\":["
                + "{\"id\":\"SUBMITTED\",\"label\":\"Submitted\",\"status\":\"COMPLETED\",\"requiredApprovals\":null,\"completedApprovals\":null,\"approvals\":[]},"
                + "{\"id\":\"SECURITY_REVIEW\",\"label\":\"Security Review\",\"status\":\"COMPLETED\",\"requiredApprovals\":1,\"completedApprovals\":1,"
                + "\"approvals\":[{\"actorId\":\"sec-1\",\"actorRole\":\"SECURITY_REVIEWER\",\"decision\":\"APPROVE\",\"createdAt\":\"2026-08-26T10:00:00Z\"}]},"
                + "{\"id\":\"MANAGER_APPROVAL\",\"label\":\"Manager Approval\",\"status\":\"IN_PROGRESS\",\"requiredApprovals\":1,\"completedApprovals\":0,\"approvals\":[]}"
                + "]}")));
```
with:
```java
wireMock.stubFor(get(urlEqualTo("/approvals/req-1/workflow-view"))
        .willReturn(okJson("{"
                + "\"workflowId\":\"privileged-access\",\"workflowVersion\":1,\"currentState\":\"MANAGER_APPROVAL\","
                + "\"terminalStates\":[\"APPROVED\",\"REJECTED\",\"EXPIRED\"],"
                + "\"stages\":["
                + "{\"id\":\"SUBMITTED\",\"label\":\"Submitted\",\"status\":\"COMPLETED\",\"requiredApprovals\":null,\"completedApprovals\":null,\"approvals\":[]},"
                + "{\"id\":\"SECURITY_REVIEW\",\"label\":\"Security Review\",\"status\":\"COMPLETED\",\"requiredApprovals\":1,\"completedApprovals\":1,"
                + "\"approvals\":[{\"actorId\":\"sec-1\",\"actorRole\":\"SECURITY_REVIEWER\",\"decision\":\"APPROVE\",\"createdAt\":\"2026-08-26T10:00:00Z\"}]},"
                + "{\"id\":\"MANAGER_APPROVAL\",\"label\":\"Manager Approval\",\"status\":\"IN_PROGRESS\",\"requiredApprovals\":1,\"completedApprovals\":0,\"approvals\":[]}"
                + "],"
                + "\"availableActions\":["
                + "{\"name\":\"approve\",\"allowedRoles\":[\"MANAGER\"],\"requiredApprovals\":1,\"currentApprovals\":0},"
                + "{\"name\":\"reject\",\"allowedRoles\":[\"MANAGER\"],\"requiredApprovals\":null,\"currentApprovals\":null}"
                + "]}")));
```
and add these two assertions at the end of the test method (after the existing `assertThat(view.stages().get(2).status())...` line):
```java
assertThat(view.availableActions()).hasSize(2);
assertThat(view.availableActions().get(0).name()).isEqualTo("approve");
assertThat(view.availableActions().get(0).allowedRoles()).containsExactly("MANAGER");
```

- [ ] **Step 3: Update `ui.html`** — add an `updateActionButtons` function driven by `availableActions`, matched against the `actorRole` input field (`#d-role`); call it from `renderPipeline` and whenever the role field changes. Replace the `renderPipeline` function:
```javascript
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
with:
```javascript
let lastWorkflowView = null;

function renderPipeline(view) {
  lastWorkflowView = view;
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
  updateActionButtons(view);
}

// Advisory only, per the workflow-view API's own contract: this only decides which
// buttons are SHOWN. The command endpoints independently re-validate role/state/guard
// server-side regardless of what's visible here.
function updateActionButtons(view) {
  const role = document.getElementById('d-role').value;
  const actions = (view && view.availableActions) || [];
  ['approve', 'reject', 'cancel'].forEach(name => {
    const btn = document.getElementById('btn-' + name);
    const action = actions.find(a => a.name === name);
    const allowed = !!action && (action.allowedRoles.length === 0 || action.allowedRoles.includes(role));
    btn.style.display = allowed ? '' : 'none';
  });
}
```
Add an `oninput` handler to the `d-role` input so switching roles live-updates which buttons are visible without waiting for the next SSE event — change:
```html
<div class="field"><label style="font-size:11px;color:#555">actorRole</label><input id="d-role" value="TRANSFER_CHECKER"></div>
```
to:
```html
<div class="field"><label style="font-size:11px;color:#555">actorRole</label><input id="d-role" value="TRANSFER_CHECKER" oninput="updateActionButtons(lastWorkflowView)"></div>
```
And in `resetPipeline()`, add `lastWorkflowView = null;` alongside the other resets, so a freshly submitted/loaded transfer starts with all three buttons hidden until the first `workflow-view` event arrives — change:
```javascript
function resetPipeline() {
  document.getElementById('pipeline').innerHTML = '';
  setStage('release', 'pending');
  document.getElementById('decision-panel').classList.remove('show');
  document.getElementById('console').innerHTML = '';
  document.getElementById('timeline').innerHTML = '<div class="empty">Waiting for events...</div>';
}
```
to:
```javascript
function resetPipeline() {
  lastWorkflowView = null;
  document.getElementById('pipeline').innerHTML = '';
  setStage('release', 'pending');
  document.getElementById('decision-panel').classList.remove('show');
  document.getElementById('console').innerHTML = '';
  document.getElementById('timeline').innerHTML = '<div class="empty">Waiting for events...</div>';
}
```

- [ ] **Step 4: Run the `banking-service` test suite**

```bash
cd banking-service && ./gradlew test
```

- [ ] **Step 5: Manual smoke test** — start Postgres (`docker compose up -d postgres`), start both services, open `banking-service/src/main/resources/static/ui.html` in a browser, submit a transfer large enough to land on `transfer-high-value` (amount ≥ the `single-checker-ceiling-minor-units` config value), and confirm: with `actorRole` set to `TRANSFER_CHECKER`, Approve/Reject/Cancel are all visible; changing `actorRole` to something else (e.g. `AUDITOR`) hides Approve/Reject immediately (Cancel has no `allowedRoles` restriction server-side, so it stays visible regardless of role — matches `transfer-high-value.yaml`'s `cancel` transition, which only gates on `actor_is_maker`, an identity check independent of role).

- [ ] **Step 6: Commit**

```bash
git add banking-service/
git commit -m "$(cat <<'EOF'
feat: UI action buttons driven by workflow-view's availableActions

Approve/Reject/Cancel buttons in the dev console now show/hide based on
whether the availableActions entry for that action's allowedRoles
includes the currently-entered actorRole. Advisory only -- the command
endpoints remain the real authorization boundary, unchanged by this.
EOF
)"
```

---

## Self-Review

**Spec coverage** (against `docs/superpowers/specs/2026-08-26-workflow-versioning-and-role-authority-design.md`):
- §1 (domain selects exact workflowId+version, engine stays domain-blind) → Task 3 Step 2, Task 1 Step 16 (`create()`'s exact `workflowRegistry.get(cmd.workflowId(), cmd.workflowVersion())`).
- §2 (version-keyed registry, used only at creation/startup) → Task 1 Step 4.
- §3 (roles/quorum on the transition; intrinsic role-check; `actor_is_not_maker`) → Task 1 Steps 1, 2, 7, 9–10, 16.
- §3.5 (three transfer workflows, `no_approval_required`/`approval_required` deleted) → Task 1 Steps 7, 9.
- §4 (`PolicySnapshot` embeds `WorkflowDefinition`) → Task 1 Step 8.
- §6 (`guards` list, AND-composed; dispatch reads the row, not the registry) → Task 1 Steps 1, 16, 17.
- §7 (`availableActions`) → Task 2 Steps 2–3, Task 4 Steps 1–3.
- §9 (migration = drop and recreate, no backfill) → implicit; no migration script anywhere in this plan, matching the spec.
- §10 (required tests: registry versioning, exact-lookup rejects unknown pairs, snapshot self-containment is *implied* by every post-creation operation reading `policy_snapshot.workflow()` rather than the registry — not independently re-tested with a "delete the YAML file" test, since Task 1's `ApprovalCommandServiceApproveTest`/`PrivilegedAccessWorkflowTest` already never touch the registry post-creation by construction. Noted as a deliberate scope call: the spec's suggested "swap in a mock WorkflowRegistry that throws" test would require restructuring `ApprovalCommandService`'s constructor injection for testability in a way disproportionate to what it'd additionally prove.

**Placeholder scan:** none found — every step has real, complete file content or a real command.

**Type consistency:** `Transition`, `GuardContext`, `PolicySnapshot`, `CreateApprovalRequest`, `WorkflowViewDto`/`AvailableActionDto` (both approval-engine and banking-service copies) are used with matching field names/order across every task that references them.
