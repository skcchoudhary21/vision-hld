# Transfer Approval System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a two-service Spring Boot maker-checker transfer approval system (transfer-service + approval-engine) with correct concurrency/idempotency handling, deployable via one `docker-compose up`.

**Architecture:** Approval Engine owns a small YAML-defined workflow (states/transitions/guards) with optimistic-concurrency-controlled transitions, an append-only audit log, an SLA expiry sweeper, and a transactional outbox. Transfer Service owns transfer validation, policy resolution, a stubbed core-banking client, and release orchestration triggered only by consuming the engine's `ApprovalApproved` event (one release path for both auto- and N-approver flows). The two services talk sync REST for commands and outbox-relayed async HTTP for lifecycle events — no broker.

**Tech Stack:** Java 21, Spring Boot 4.1.x, Gradle 8.14.3, Spring Data JPA, Postgres (one DB per service), Lombok, Testcontainers + JUnit 5, WireMock (cross-service test doubles), Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-25-transfer-approval-design.md`

## Global Constraints

- Java 21 / Spring Boot 4.1.x / Gradle 8.14.3 — exact versions, not "latest". **Amendment (Task 1 execution finding):** the plan originally said Gradle 8.11, but Spring Boot 4.1.1's Gradle plugin requires Gradle 8.14+ or 9.x — 8.11 fails outright at plugin-apply time. Verified 8.14.3 works end-to-end (compile + test) with the toolchain block below. Every `settings.gradle.kts` (Tasks 1 and 10) must also add `id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"` — Gradle 8.14.3 refuses to auto-provision a JDK 21 toolchain without a configured download resolver, so `java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }` (the original, correct form — do not replace it with `sourceCompatibility`/`release.set`) only works with this resolver present.
- One Postgres DB per service; a service never writes the other's schema.
- No message broker anywhere — lifecycle events move via a DB-backed transactional outbox + polling HTTP relay only.
- Two fully independent Gradle builds (`transfer-service/`, `approval-engine/`) — no shared parent module, no shared DTO jar. Small duplication of the event JSON shape across services is intentional.
- Core Banking is never a third deployable service — it is `CoreBankingClient` (interface) + a stub implementation inside `transfer-service`.
- Workflow YAML loader has a 2-hour tripwire (Task 1): if it's not working with validation + guard registry + one end-to-end test inside that budget, stop and switch to the Task 1 fallback (hardcoded `EnumMap`), and note the trade-off in the README.
- No domain rules (`amount > threshold`, `balance >= amount`, etc.) inside Approval Engine guards — engine guards are generic only (`approval_required`, `approvals_satisfied`, `actor_is_maker`, `actor_is_eligible_checker`, `sla_expired`).
- Every competing *transition attempt* uses one mechanism: a single guarded conditional `UPDATE ... WHERE state = :expected AND version = :expected` — no `@Version`-exception-based locking, no special-cased race handling. **Amendment (post-review):** `approve`/`reject`/`cancel` additionally take a `SELECT ... FOR UPDATE` row lock on the request *before* counting decisions, because quorum counting is an aggregate read, not a transition — two concurrent transactions under READ COMMITTED can each undercount (neither sees the other's uncommitted decision) and both skip the transition entirely, stranding a quorum-satisfied request in `PENDING_APPROVAL` forever. The guarded UPDATE alone only protects the transition *attempt*, not the decision of whether to attempt one. The expiry sweeper still needs no explicit lock of its own — its plain guarded UPDATE naturally blocks behind a concurrent `approve`/`reject`/`cancel`'s row lock and re-checks fresh state once unblocked.
- **Never call a `@Transactional` method on `this` from within the same class.** Spring's proxy-based AOP does not intercept self-invocation, so the annotation is silently a no-op — the method runs with no transaction (each repository call gets its own ad-hoc one instead). Any transactional unit that a scheduled/looping method needs must live on a separate injected bean, called through the real proxy.
- Controller/MockMvc tests are lowest priority (spec §20) — cover happy path + one 409 case per endpoint, do not over-invest.
- `spring.jpa.hibernate.ddl-auto=update` is the deliberate schema-management trade-off for this exercise (no Flyway) — state this in the README, don't silently deviate from it mid-plan.

---

## Part A — Approval Engine

### Task 1: Approval Engine scaffold + workflow definition loader (2-hour tripwire)

**Files:**
- Modify: `.gitignore` (repo root — already exists with `.worktrees/`; append Gradle build output ignores for both services; this is the only repo-root `.gitignore` needed, Task 10 doesn't repeat it)
- Create: `approval-engine/settings.gradle.kts`
- Create: `approval-engine/build.gradle.kts`
- Create: `approval-engine/src/main/java/com/visionbank/approval/ApprovalEngineApplication.java`
- Create: `approval-engine/src/main/resources/application.yml`
- Create: `approval-engine/src/main/resources/workflow/transfer-approval.yaml`
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalState.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/Transition.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowDefinition.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowLoader.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowLoaderTest.java`

**Interfaces:**
- Produces: `ApprovalState` enum (`SUBMITTED, PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED, EXPIRED`); `Transition(String name, ApprovalState from, ApprovalState to, String guard)`; `WorkflowDefinition.transitionsFrom(ApprovalState state) -> List<Transition>`; `WorkflowLoader.load(String classpathResource) -> WorkflowDefinition`.

- [ ] **Step 1: Append Gradle build-output ignores to the repo-root `.gitignore`, then create the Gradle scaffold**

Append to the existing repo-root `.gitignore` (it currently has only `.worktrees/`):
```
build/
.gradle/
gradlew.bat
```
`gradlew.bat` is ignored deliberately — this project only ships the Unix `gradlew`; the `.bat` wrapper isn't generated and shouldn't be. `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` are NOT ignored — those are meant to be committed (that's the point of a Gradle wrapper: reproducible builds without a global Gradle install).

`approval-engine/settings.gradle.kts`:
```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "approval-engine"
```

`approval-engine/build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.visionbank"
version = "0.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.yaml:snakeyaml")
    runtimeOnly("org.postgresql:postgresql")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}

tasks.withType<Test> { useJUnitPlatform() }
```

`approval-engine/src/main/java/com/visionbank/approval/ApprovalEngineApplication.java`:
```java
package com.visionbank.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApprovalEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApprovalEngineApplication.class, args);
    }
}
```

`approval-engine/src/main/resources/application.yml`:
```yaml
server:
  port: 8081
spring:
  application:
    name: approval-engine
  datasource:
    # stringtype=unspecified: several columns (payload, policy_snapshot,
    # outbox.payload, idempotency_key.result) are jsonb fed by a plain Java
    # String (via AttributeConverter or a direct String field). Without this,
    # the Postgres JDBC driver binds the String as varchar and Postgres
    # rejects it ("column is jsonb but expression is character varying").
    url: jdbc:postgresql://localhost:5433/approval?stringtype=unspecified
    username: approval
    password: approval
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        format_sql: true
workflow:
  definition-path: workflow/transfer-approval.yaml
transfer-service:
  webhook-url: http://localhost:8080/internal/events
```

Generate the real Gradle wrapper now, not in Task 16 — every later step in this plan runs `./gradlew ...`, so it must exist starting here:
```bash
cd approval-engine && gradle wrapper --gradle-version 8.14.3 && chmod +x gradlew && cd ..
```
Commit `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` along with the rest of this task's files — they are not build output, they're what makes the build reproducible without a global Gradle install. If `gradle` isn't on `PATH` locally, download `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`, extract it, and run `wrapper --gradle-version 8.14.3` from its `bin/gradle` once.

- [ ] **Step 2: Write the workflow YAML definition**

`approval-engine/src/main/resources/workflow/transfer-approval.yaml`:
```yaml
name: transfer-approval
version: 1
states: [SUBMITTED, PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED, EXPIRED]
initialState: SUBMITTED
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
```

- [ ] **Step 3: Write the model types and the failing loader test**

`approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalState.java`:
```java
package com.visionbank.approval.domain;

public enum ApprovalState {
    SUBMITTED, PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED, EXPIRED
}
```

`approval-engine/src/main/java/com/visionbank/approval/workflow/Transition.java`:
```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;

public record Transition(String name, ApprovalState from, ApprovalState to, String guard) {}
```

`approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowDefinition.java`:
```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;
import java.util.List;
import java.util.stream.Collectors;

public record WorkflowDefinition(
        String name,
        int version,
        List<ApprovalState> states,
        ApprovalState initialState,
        List<Transition> transitions) {

    public List<Transition> transitionsFrom(ApprovalState state) {
        return transitions.stream()
                .filter(t -> t.from() == state)
                .collect(Collectors.toList());
    }

    public Transition byName(String name) {
        return transitions.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown transition: " + name));
    }
}
```

`approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowLoaderTest.java`:
```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLoaderTest {

    @Test
    void loadsDefinitionFromClasspathYaml() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.name()).isEqualTo("transfer-approval");
        assertThat(def.initialState()).isEqualTo(ApprovalState.SUBMITTED);
        assertThat(def.states()).containsExactlyInAnyOrder(
                ApprovalState.SUBMITTED, ApprovalState.PENDING_APPROVAL, ApprovalState.APPROVED,
                ApprovalState.REJECTED, ApprovalState.CANCELLED, ApprovalState.EXPIRED);
    }

    @Test
    void transitionsFromSubmittedIncludeAutoApproveAndRequireApproval() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.transitionsFrom(ApprovalState.SUBMITTED))
                .extracting(Transition::name)
                .containsExactlyInAnyOrder("auto_approve", "require_approval");
    }

    @Test
    void approveTransitionGuardIsApprovalsSatisfied() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.byName("approve").guard()).isEqualTo("approvals_satisfied");
        assertThat(def.byName("approve").to()).isEqualTo(ApprovalState.APPROVED);
    }

    @Test
    void loadingDefinitionWithDuplicateTransitionNamesFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-duplicate-transition.yaml"));
    }
}
```

Also create the malformed fixture this last test loads:

`approval-engine/src/test/resources/workflow/invalid-duplicate-transition.yaml`:
```yaml
name: broken
version: 1
states: [SUBMITTED, APPROVED]
initialState: SUBMITTED
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

- [ ] **Step 4: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests WorkflowLoaderTest`
Expected: FAIL — `YamlWorkflowLoader` does not exist.

**Tripwire checkpoint:** you're allowed up to ~2 hours total for this task including this step and the next. If you're still fighting SnakeYAML/config binding after that, stop and implement the fallback in Step 5b instead of Step 5a — do not keep debugging past the tripwire.

- [ ] **Step 5a (primary): Implement the YAML-backed `WorkflowLoader`**

`approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowLoader.java`:
```java
package com.visionbank.approval.workflow;

public interface WorkflowLoader {
    WorkflowDefinition load(String classpathResource);
}
```

`approval-engine/src/main/java/com/visionbank/approval/workflow/YamlWorkflowLoader.java`:
```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class YamlWorkflowLoader implements WorkflowLoader {

    @Override
    @SuppressWarnings("unchecked")
    public WorkflowDefinition load(String classpathResource) {
        try (InputStream in = new ClassPathResource(classpathResource).getInputStream()) {
            Map<String, Object> raw = new Yaml().load(in);

            String name = (String) raw.get("name");
            int version = (Integer) raw.get("version");
            List<ApprovalState> states = ((List<String>) raw.get("states")).stream()
                    .map(ApprovalState::valueOf)
                    .collect(Collectors.toList());
            ApprovalState initial = ApprovalState.valueOf((String) raw.get("initialState"));

            List<Transition> transitions = ((List<Map<String, String>>) raw.get("transitions")).stream()
                    .map(t -> new Transition(
                            t.get("name"),
                            ApprovalState.valueOf(t.get("from")),
                            ApprovalState.valueOf(t.get("to")),
                            t.get("guard")))
                    .collect(Collectors.toList());

            WorkflowDefinition definition = new WorkflowDefinition(name, version, states, initial, transitions);
            validate(definition);
            return definition;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow definition: " + classpathResource, e);
        }
    }

    private void validate(WorkflowDefinition def) {
        if (!def.states().contains(def.initialState())) {
            throw new IllegalStateException("initialState " + def.initialState() + " is not declared in states[]");
        }
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (Transition t : def.transitions()) {
            if (!def.states().contains(t.from()) || !def.states().contains(t.to())) {
                throw new IllegalStateException("Transition " + t.name() + " references a state not in states[]");
            }
            if (!seenNames.add(t.name())) {
                throw new IllegalStateException("Duplicate transition name: " + t.name());
            }
        }
        // Guard names are validated separately in WorkflowConfig (Task 4), once the
        // GuardRegistry bean exists — this loader has no dependency on it.
    }
}
```

- [ ] **Step 5b (fallback, only if the tripwire triggered): hardcoded `EnumMap` loader**

Skip this step if 5a passed within budget. Otherwise replace `YamlWorkflowLoader` with:

```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;
import java.util.List;

public class HardcodedWorkflowLoader implements WorkflowLoader {

    @Override
    public WorkflowDefinition load(String ignoredClasspathResource) {
        List<Transition> transitions = List.of(
                new Transition("auto_approve", ApprovalState.SUBMITTED, ApprovalState.APPROVED, "no_approval_required"),
                new Transition("require_approval", ApprovalState.SUBMITTED, ApprovalState.PENDING_APPROVAL, "approval_required"),
                new Transition("approve", ApprovalState.PENDING_APPROVAL, ApprovalState.APPROVED, "approvals_satisfied"),
                new Transition("reject", ApprovalState.PENDING_APPROVAL, ApprovalState.REJECTED, "actor_is_eligible_checker"),
                new Transition("cancel", ApprovalState.PENDING_APPROVAL, ApprovalState.CANCELLED, "actor_is_maker"),
                new Transition("expire", ApprovalState.PENDING_APPROVAL, ApprovalState.EXPIRED, "sla_expired"));
        return new WorkflowDefinition("transfer-approval", 1, List.of(ApprovalState.values()), ApprovalState.SUBMITTED, transitions);
    }
}
```

If you use the fallback, add one line to the README trade-offs section: *"Workflow definition is a hardcoded transition table, not YAML-loaded — the 2-hour tripwire on the declarative loader was hit; the engine/domain boundary this seam demonstrates is unaffected."*

- [ ] **Step 6: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests WorkflowLoaderTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): scaffold project and workflow definition loader"
```

---

### Task 2: Guard registry + standard guards

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/PolicySnapshot.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/Guard.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/GuardContext.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/GuardRegistry.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/workflow/StandardGuards.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/workflow/StandardGuardsTest.java`

**Interfaces:**
- Consumes: `ApprovalState` (Task 1).
- Produces: `PolicySnapshot(String policyVersion, int requiredApprovals, List<String> eligibleRoles, boolean makerCanApprove)`; `Guard.evaluate(GuardContext ctx) -> boolean`; `GuardContext(String makerId, PolicySnapshot policy, long currentApprovalCount, String actorId, String actorRole, boolean slaExpired)`; `GuardRegistry.get(String name) -> Guard`. Task 5 (command service) consumes `GuardRegistry.get(...)` by the exact guard names from the Task 1 YAML (`no_approval_required`, `approval_required`, `approvals_satisfied`, `actor_is_maker`, `actor_is_eligible_checker`, `sla_expired`).

- [ ] **Step 1: Write `PolicySnapshot` and the failing guard tests**

`approval-engine/src/main/java/com/visionbank/approval/domain/PolicySnapshot.java`:
```java
package com.visionbank.approval.domain;

import java.util.List;

public record PolicySnapshot(
        String policyVersion,
        int requiredApprovals,
        List<String> eligibleRoles,
        boolean makerCanApprove) {}
```

`approval-engine/src/main/java/com/visionbank/approval/workflow/GuardContext.java`:
```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.PolicySnapshot;

public record GuardContext(
        String makerId,
        PolicySnapshot policy,
        long currentApprovalCount,
        String actorId,
        String actorRole,
        boolean slaExpired) {}
```

`approval-engine/src/test/java/com/visionbank/approval/workflow/StandardGuardsTest.java`:
```java
package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.PolicySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandardGuardsTest {

    private final GuardRegistry registry = StandardGuards.buildRegistry();

    private PolicySnapshot policy(int required, boolean makerCanApprove) {
        return new PolicySnapshot("v1", required, List.of("TRANSFER_CHECKER"), makerCanApprove);
    }

    @Test
    void noApprovalRequiredWhenRequiredApprovalsIsZero() {
        GuardContext ctx = new GuardContext("maker-1", policy(0, false), 0, null, null, false);
        assertThat(registry.get("no_approval_required").evaluate(ctx)).isTrue();
        assertThat(registry.get("approval_required").evaluate(ctx)).isFalse();
    }

    @Test
    void approvalRequiredWhenRequiredApprovalsPositive() {
        GuardContext ctx = new GuardContext("maker-1", policy(2, false), 0, null, null, false);
        assertThat(registry.get("approval_required").evaluate(ctx)).isTrue();
        assertThat(registry.get("no_approval_required").evaluate(ctx)).isFalse();
    }

    @Test
    void approvalsSatisfiedComparesCountToRequired() {
        GuardContext under = new GuardContext("maker-1", policy(2, false), 1, null, null, false);
        GuardContext at = new GuardContext("maker-1", policy(2, false), 2, null, null, false);
        assertThat(registry.get("approvals_satisfied").evaluate(under)).isFalse();
        assertThat(registry.get("approvals_satisfied").evaluate(at)).isTrue();
    }

    @Test
    void actorIsMakerComparesActorIdToMakerId() {
        GuardContext ctx = new GuardContext("maker-1", policy(1, false), 0, "maker-1", "MAKER", false);
        GuardContext other = new GuardContext("maker-1", policy(1, false), 0, "checker-1", "TRANSFER_CHECKER", false);
        assertThat(registry.get("actor_is_maker").evaluate(ctx)).isTrue();
        assertThat(registry.get("actor_is_maker").evaluate(other)).isFalse();
    }

    @Test
    void actorIsEligibleCheckerComparesRoleToPolicyRoles() {
        GuardContext eligible = new GuardContext("maker-1", policy(1, false), 0, "checker-1", "TRANSFER_CHECKER", false);
        GuardContext ineligible = new GuardContext("maker-1", policy(1, false), 0, "someone", "AUDITOR", false);
        assertThat(registry.get("actor_is_eligible_checker").evaluate(eligible)).isTrue();
        assertThat(registry.get("actor_is_eligible_checker").evaluate(ineligible)).isFalse();
    }

    @Test
    void slaExpiredReflectsContextFlag() {
        GuardContext expired = new GuardContext("maker-1", policy(1, false), 0, null, null, true);
        GuardContext notExpired = new GuardContext("maker-1", policy(1, false), 0, null, null, false);
        assertThat(registry.get("sla_expired").evaluate(expired)).isTrue();
        assertThat(registry.get("sla_expired").evaluate(notExpired)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests StandardGuardsTest`
Expected: FAIL — `Guard`, `GuardRegistry`, `StandardGuards` do not exist.

- [ ] **Step 3: Implement `Guard`, `GuardRegistry`, `StandardGuards`**

`approval-engine/src/main/java/com/visionbank/approval/workflow/Guard.java`:
```java
package com.visionbank.approval.workflow;

@FunctionalInterface
public interface Guard {
    boolean evaluate(GuardContext ctx);
}
```

`approval-engine/src/main/java/com/visionbank/approval/workflow/GuardRegistry.java`:
```java
package com.visionbank.approval.workflow;

import java.util.HashMap;
import java.util.Map;

public class GuardRegistry {
    private final Map<String, Guard> guards = new HashMap<>();

    public void register(String name, Guard guard) {
        guards.put(name, guard);
    }

    public Guard get(String name) {
        Guard guard = guards.get(name);
        if (guard == null) {
            throw new IllegalStateException("No guard registered for name: " + name);
        }
        return guard;
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/workflow/StandardGuards.java`:
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
        registry.register("no_approval_required", ctx -> ctx.policy().requiredApprovals() == 0);
        registry.register("approval_required", ctx -> ctx.policy().requiredApprovals() > 0);
        registry.register("approvals_satisfied", ctx -> ctx.currentApprovalCount() >= ctx.policy().requiredApprovals());
        registry.register("actor_is_maker", ctx -> ctx.actorId() != null && ctx.actorId().equals(ctx.makerId()));
        registry.register("actor_is_eligible_checker", ctx -> ctx.actorRole() != null && ctx.policy().eligibleRoles().contains(ctx.actorRole()));
        registry.register("sla_expired", GuardContext::slaExpired);
        return registry;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests StandardGuardsTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): guard registry and standard guards"
```

---

### Task 3: JPA entities, repositories, and the guarded conditional-update query

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/PolicySnapshotConverter.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalRequest.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalDecision.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/AuditLog.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/OutboxEvent.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/domain/IdempotencyRecord.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/repository/ApprovalRequestRepository.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/repository/ApprovalDecisionRepository.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/repository/AuditLogRepository.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/repository/OutboxEventRepository.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/repository/IdempotencyRecordRepository.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/repository/ApprovalRequestRepositoryTest.java`

**Interfaces:**
- Consumes: `PolicySnapshot`, `ApprovalState` (Tasks 1-2).
- Produces: `ApprovalRequestRepository.guardedTransition(String requestId, ApprovalState expectedState, long expectedVersion, ApprovalState newState) -> int` (the single mechanism every later task uses for every transition); `ApprovalRequestRepository.findByRequestId(String) -> Optional<ApprovalRequest>`; `ApprovalDecisionRepository.countByRequestId(String) -> long`; entity setters/getters used verbatim by Tasks 4-9.

- [ ] **Step 1: Write the JSONB converter and the failing repository test**

`approval-engine/src/main/java/com/visionbank/approval/domain/PolicySnapshotConverter.java`:
```java
package com.visionbank.approval.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class PolicySnapshotConverter implements AttributeConverter<PolicySnapshot, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(PolicySnapshot attribute) {
        try {
            return attribute == null ? null : MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize PolicySnapshot", e);
        }
    }

    @Override
    public PolicySnapshot convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null : MAPPER.readValue(dbData, PolicySnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize PolicySnapshot", e);
        }
    }
}
```

`approval-engine/src/test/java/com/visionbank/approval/repository/ApprovalRequestRepositoryTest.java`:
```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.PolicySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class ApprovalRequestRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ApprovalRequestRepository repository;

    private ApprovalRequest newRequest(String id) {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId(id);
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState(ApprovalState.PENDING_APPROVAL);
        r.setVersion(0L);
        r.setMakerId("maker-1");
        r.setPolicySnapshot(new PolicySnapshot("v1", 2, List.of("TRANSFER_CHECKER"), false));
        r.setPayload("{}");
        r.setCreatedAt(Instant.now());
        r.setExpiresAt(Instant.now().plusSeconds(86400));
        return r;
    }

    @Test
    void guardedTransitionSucceedsWhenStateAndVersionMatch() {
        repository.saveAndFlush(newRequest("req-1"));

        int rows = repository.guardedTransition("req-1", ApprovalState.PENDING_APPROVAL, 0L, ApprovalState.APPROVED);

        assertThat(rows).isEqualTo(1);
        Optional<ApprovalRequest> reloaded = repository.findByRequestId("req-1");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(reloaded.get().getVersion()).isEqualTo(1L);
    }

    @Test
    void guardedTransitionFailsWhenVersionStale() {
        repository.saveAndFlush(newRequest("req-2"));

        int rows = repository.guardedTransition("req-2", ApprovalState.PENDING_APPROVAL, 5L, ApprovalState.APPROVED);

        assertThat(rows).isEqualTo(0);
        assertThat(repository.findByRequestId("req-2").get().getState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
    }

    @Test
    void guardedTransitionFailsWhenStateStale() {
        repository.saveAndFlush(newRequest("req-3"));

        int rows = repository.guardedTransition("req-3", ApprovalState.APPROVED, 0L, ApprovalState.EXPIRED);

        assertThat(rows).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests ApprovalRequestRepositoryTest`
Expected: FAIL — entities/repository do not exist.

- [ ] **Step 3: Implement the entities**

`approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalRequest.java`:
```java
package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "approval_request")
@Getter
@Setter
public class ApprovalRequest {

    @Id
    @Column(name = "request_id")
    private String requestId;

    @Column(name = "request_type", nullable = false)
    private String requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ApprovalState state;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "maker_id", nullable = false)
    private String makerId;

    @Convert(converter = PolicySnapshotConverter.class)
    @Column(name = "policy_snapshot", columnDefinition = "jsonb", nullable = false)
    private PolicySnapshot policySnapshot;

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
```

`approval-engine/src/main/java/com/visionbank/approval/domain/ApprovalDecision.java`:
```java
package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "approval_decision", uniqueConstraints = @UniqueConstraint(columnNames = {"request_id", "actor_id"}))
@Getter
@Setter
public class ApprovalDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "decision_id")
    private String decisionId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "actor_role", nullable = false)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    private DecisionType decision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum DecisionType { APPROVE, REJECT }
}
```

`approval-engine/src/main/java/com/visionbank/approval/domain/AuditLog.java`:
```java
package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "audit_id")
    private String auditId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(name = "action", nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", nullable = false)
    private ApprovalState previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_state", nullable = false)
    private ApprovalState newState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "metadata")
    private String metadata;
}
```

`approval-engine/src/main/java/com/visionbank/approval/domain/OutboxEvent.java`:
```java
package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "outbox")
@Getter
@Setter
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;
}
```

`approval-engine/src/main/java/com/visionbank/approval/domain/IdempotencyRecord.java`:
```java
package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
@Getter
@Setter
public class IdempotencyRecord {

    @Id
    @Column(name = "idem_key")
    private String key;

    @Column(name = "command_type", nullable = false)
    private String commandType;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "result", columnDefinition = "jsonb", nullable = false)
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

- [ ] **Step 4: Implement the repositories, including the guarded conditional update**

`approval-engine/src/main/java/com/visionbank/approval/repository/ApprovalRequestRepository.java`:
```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.ApprovalState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    Optional<ApprovalRequest> findByRequestId(String requestId);

    /**
     * The single concurrency mechanism for every transition in the engine.
     * Returns 1 if this call won the race, 0 if the state/version had already
     * moved (lost race or illegal transition — caller re-reads to distinguish).
     */
    @Modifying
    @Query("UPDATE ApprovalRequest a SET a.state = :newState, a.version = a.version + 1 " +
           "WHERE a.requestId = :requestId AND a.state = :expectedState AND a.version = :expectedVersion")
    int guardedTransition(@Param("requestId") String requestId,
                           @Param("expectedState") ApprovalState expectedState,
                           @Param("expectedVersion") long expectedVersion,
                           @Param("newState") ApprovalState newState);

    List<ApprovalRequest> findByStateAndExpiresAtBefore(ApprovalState state, Instant cutoff);

    /**
     * Row lock for approve/reject/cancel (Task 5), taken before counting
     * decisions. Quorum counting is an aggregate read, not a single-row
     * transition — without this lock, two concurrent approvers can each
     * undercount (neither sees the other's uncommitted decision) and both
     * skip the transition, stranding a quorum-satisfied request forever.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ApprovalRequest a WHERE a.requestId = :requestId")
    Optional<ApprovalRequest> findByRequestIdForUpdate(@Param("requestId") String requestId);
}
```

`approval-engine/src/main/java/com/visionbank/approval/repository/ApprovalDecisionRepository.java`:
```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, String> {
    long countByRequestIdAndDecision(String requestId, ApprovalDecision.DecisionType decision);
    boolean existsByRequestIdAndActorId(String requestId, String actorId);
}
```

`approval-engine/src/main/java/com/visionbank/approval/repository/AuditLogRepository.java`:
```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
}
```

`approval-engine/src/main/java/com/visionbank/approval/repository/OutboxEventRepository.java`:
```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc();

    /**
     * Locks and returns the ids of a batch of unpublished, unclaimed (or
     * stale-claimed) events, skipping any row a concurrent relay instance
     * already has locked. Must be called inside the same short transaction
     * as markClaimed below — no HTTP call between them.
     */
    @Query(value = "SELECT event_id FROM outbox " +
                    "WHERE published_at IS NULL AND (claimed_at IS NULL OR claimed_at < :staleBefore) " +
                    "ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<String> selectAndLockUnpublishedIds(@Param("staleBefore") Instant staleBefore, @Param("batchSize") int batchSize);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.claimedAt = :now WHERE o.eventId IN :ids")
    void markClaimed(@Param("ids") List<String> ids, @Param("now") Instant now);
}
```

`approval-engine/src/main/java/com/visionbank/approval/repository/IdempotencyRecordRepository.java`:
```java
package com.visionbank.approval.repository;

import com.visionbank.approval.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests ApprovalRequestRepositoryTest`
Expected: PASS (3 tests). Requires Docker running locally for Testcontainers.

- [ ] **Step 6: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): entities, repositories, guarded conditional update"
```

---

### Task 4: `ApprovalCommandService.create` — initial transition + idempotent submission

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/CreateApprovalRequest.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalRequestView.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/IdempotencyConflictException.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceCreateTest.java`

**Interfaces:**
- Consumes: `ApprovalRequestRepository`, `ApprovalDecisionRepository`, `AuditLogRepository`, `OutboxEventRepository`, `IdempotencyRecordRepository` (Task 3); `WorkflowDefinition`, `GuardRegistry`, `GuardContext` (Tasks 1-2).
- Produces: `ApprovalCommandService.create(CreateApprovalRequest cmd, String idempotencyKey) -> ApprovalRequestView`; `CreateApprovalRequest(String requestId, String requestType, String makerId, PolicySnapshot policy, String payloadJson, Instant expiresAt)`; `ApprovalRequestView(String requestId, ApprovalState state, long version)`. Tasks 5 and 9 consume this same `create` signature and `ApprovalRequestView`.

- [ ] **Step 1: Write the failing test**

`approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceCreateTest.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.PolicySnapshot;
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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ApprovalCommandService service;

    private CreateApprovalRequest cmd(String requestId, int requiredApprovals) {
        return new CreateApprovalRequest(
                requestId, "TRANSFER_APPROVAL", "maker-1",
                new PolicySnapshot("v1", requiredApprovals, List.of("TRANSFER_CHECKER"), false),
                "{\"transferId\":\"" + requestId + "\"}",
                Instant.now().plusSeconds(86400));
    }

    @Test
    void zeroRequiredApprovalsAutoApproves() {
        ApprovalRequestView view = service.create(cmd("auto-1", 0), UUID.randomUUID().toString());

        assertThat(view.state()).isEqualTo(ApprovalState.APPROVED);
    }

    @Test
    void positiveRequiredApprovalsGoesToPendingApproval() {
        ApprovalRequestView view = service.create(cmd("pending-1", 2), UUID.randomUUID().toString());

        assertThat(view.state()).isEqualTo(ApprovalState.PENDING_APPROVAL);
    }

    @Test
    void replayingSameIdempotencyKeyReturnsSameResultWithoutSecondRequest() {
        String key = UUID.randomUUID().toString();
        ApprovalRequestView first = service.create(cmd("idem-1", 0), key);

        ApprovalRequestView second = service.create(cmd("idem-1", 0), key);

        assertThat(second.requestId()).isEqualTo(first.requestId());
        assertThat(second.state()).isEqualTo(first.state());
    }

    @Test
    void replayingSameKeyWithDifferentBodyThrowsConflict() {
        String key = UUID.randomUUID().toString();
        service.create(cmd("idem-2", 0), key);

        assertThatThrownBy(() -> service.create(cmd("idem-3", 0), key))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests ApprovalCommandServiceCreateTest`
Expected: FAIL — `ApprovalCommandService` does not exist.

- [ ] **Step 3: Implement the DTOs and the service's `create` method**

`approval-engine/src/main/java/com/visionbank/approval/service/CreateApprovalRequest.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.PolicySnapshot;

import java.time.Instant;

public record CreateApprovalRequest(
        String requestId,
        String requestType,
        String makerId,
        PolicySnapshot policy,
        String payloadJson,
        Instant expiresAt) {}
```

`approval-engine/src/main/java/com/visionbank/approval/service/ApprovalRequestView.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;

public record ApprovalRequestView(String requestId, ApprovalState state, long version) {}
```

`approval-engine/src/main/java/com/visionbank/approval/service/IdempotencyConflictException.java`:
```java
package com.visionbank.approval.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency key already used with a different request body: " + key);
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java`:
```java
package com.visionbank.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionbank.approval.domain.*;
import com.visionbank.approval.repository.*;
import com.visionbank.approval.workflow.GuardContext;
import com.visionbank.approval.workflow.GuardRegistry;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class ApprovalCommandService {

    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;
    private final IdempotencyRecordRepository idempotency;
    private final WorkflowDefinition workflow;
    private final GuardRegistry guards;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalDecisionRepository decisions,
                                   AuditLogRepository audits, OutboxEventRepository outbox,
                                   IdempotencyRecordRepository idempotency, WorkflowDefinition workflow,
                                   GuardRegistry guards) {
        this.requests = requests;
        this.decisions = decisions;
        this.audits = audits;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.workflow = workflow;
        this.guards = guards;
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

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId(cmd.requestId());
        request.setRequestType(cmd.requestType());
        request.setMakerId(cmd.makerId());
        request.setPolicySnapshot(cmd.policy());
        request.setPayload(cmd.payloadJson());
        request.setCreatedAt(Instant.now());
        request.setExpiresAt(cmd.expiresAt());
        request.setVersion(0L);
        request.setState(ApprovalState.SUBMITTED);

        GuardContext ctx = new GuardContext(cmd.makerId(), cmd.policy(), 0, null, null, false);
        Transition initial = workflow.transitionsFrom(ApprovalState.SUBMITTED).stream()
                .filter(t -> guards.get(t.guard()).evaluate(ctx))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No transition from SUBMITTED satisfied by policy"));

        request.setState(initial.to());
        request.setVersion(1L);
        requests.save(request);

        writeAudit(cmd.requestId(), null, null, "SUBMITTED", ApprovalState.SUBMITTED, initial.to());
        // Auto-approved requests emit BOTH events (spec §16) so Transfer's release
        // trigger is always "on ApprovalApproved" — no separate auto-release path.
        writeOutbox(cmd.requestId(), "ApprovalSubmitted");
        if (initial.to() == ApprovalState.APPROVED) {
            writeOutbox(cmd.requestId(), "ApprovalApproved");
        }

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

    private void writeAudit(String requestId, String actorId, String actorRole, String action,
                             ApprovalState from, ApprovalState to) {
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

Also register the loaded `WorkflowDefinition` as a bean so it can be injected. This is also where the guard-name validation from spec §7 belongs — `YamlWorkflowLoader` has no dependency on `GuardRegistry`, but this config class depends on both, so it's the first point where a transition's `guard` name can be checked against what's actually registered:

`approval-engine/src/main/java/com/visionbank/approval/workflow/WorkflowConfig.java`:
```java
package com.visionbank.approval.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfig {

    @Bean
    public WorkflowDefinition workflowDefinition(@Value("${workflow.definition-path}") String path,
                                                   GuardRegistry guards) {
        WorkflowDefinition definition = new YamlWorkflowLoader().load(path);
        // GuardRegistry.get() already throws IllegalStateException on an unknown
        // name (Task 2) — calling it here for every transition turns "guard:
        // approval_satsified" (typo) into a startup failure instead of a
        // first-request failure.
        definition.transitions().forEach(t -> guards.get(t.guard()));
        return definition;
    }
}
```

Add one test to prove this fires: append to `approval-engine/src/test/java/com/visionbank/approval/workflow/StandardGuardsTest.java`'s test list is not appropriate (that class doesn't load YAML) — instead add a small standalone test:

`approval-engine/src/test/java/com/visionbank/approval/workflow/WorkflowConfigTest.java`:
```java
package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowConfigTest {

    @Test
    void unknownGuardNameFailsAtWiringTimeNotAtFirstUse() {
        WorkflowConfig config = new WorkflowConfig();
        GuardRegistry emptyRegistry = new GuardRegistry(); // no guards registered

        assertThatThrownBy(() -> config.workflowDefinition("workflow/transfer-approval.yaml", emptyRegistry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No guard registered");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests ApprovalCommandServiceCreateTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): idempotent create command with initial transition"
```

---

### Task 5: `approve`/`reject`/`cancel` — guarded OCC, N-of-M accumulation, 409 differentiation

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/ConcurrentStateChangeException.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/InvalidStateTransitionException.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/ForbiddenActionException.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalRequestNotFoundException.java`
- Modify: `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java` (add `approve`, `reject`, `cancel`)
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceApproveTest.java`

**Interfaces:**
- Consumes: everything from Task 4's `ApprovalCommandService`, plus `ApprovalDecisionRepository.existsByRequestIdAndActorId` / `countByRequestIdAndDecision` (Task 3).
- Produces: `ApprovalCommandService.approve(String requestId, String actorId, String actorRole) -> ApprovalRequestView`; `reject(String requestId, String actorId, String actorRole) -> ApprovalRequestView`; `cancel(String requestId, String actorId) -> ApprovalRequestView`. No `idempotencyKey` parameter on these three (spec §11): a decision is naturally idempotent per `(request_id, actor_id)` via Task 3's unique constraint — retrying the same actor's decision replays current state instead of double-counting, so a second dedup mechanism here would be redundant. `create` keeps its client `idempotencyKey` since it's the command that originates a request. `ConcurrentStateChangeException(String requestId, ApprovalState currentState)`; `InvalidStateTransitionException(String requestId, ApprovalState currentState, String requestedAction)`. Task 9 (controller) maps these two exceptions to the two 409 bodies from spec §19.

- [ ] **Step 1: Write the failing tests**

`approval-engine/src/test/java/com/visionbank/approval/service/ApprovalCommandServiceApproveTest.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.PolicySnapshot;
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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ApprovalCommandService service;

    private String createPending(String requestId, int required) {
        service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
                new PolicySnapshot("v1", required, List.of("TRANSFER_CHECKER"), false),
                "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
        return requestId;
    }

    @Test
    void singleApprovalOnRequiredOneTransitionsToApproved() {
        String id = createPending("req-single", 1);

        ApprovalRequestView view = service.approve(id, "checker-1", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo(ApprovalState.APPROVED);
    }

    @Test
    void firstOfTwoRequiredApprovalsRecordsWithoutTransitioning() {
        String id = createPending("req-quorum", 2);

        ApprovalRequestView view = service.approve(id, "checker-1", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo(ApprovalState.PENDING_APPROVAL);
    }

    @Test
    void secondOfTwoRequiredApprovalsTransitionsToApproved() {
        String id = createPending("req-quorum-2", 2);
        service.approve(id, "checker-1", "TRANSFER_CHECKER");

        ApprovalRequestView view = service.approve(id, "checker-2", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo(ApprovalState.APPROVED);
    }

    @Test
    void makerCannotApproveOwnRequest() {
        String id = createPending("req-maker", 1);

        assertThatThrownBy(() -> service.approve(id, "maker-1", "TRANSFER_CHECKER"))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void ineligibleRoleCannotApprove() {
        String id = createPending("req-role", 1);

        assertThatThrownBy(() -> service.approve(id, "auditor-1", "AUDITOR"))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void approvingAlreadyTerminalRequestThrowsConcurrentStateChange() {
        String id = createPending("req-terminal", 1);
        service.cancel(id, "maker-1");

        assertThatThrownBy(() -> service.approve(id, "checker-1", "TRANSFER_CHECKER"))
                .isInstanceOf(ConcurrentStateChangeException.class);
    }

    @Test
    void approvingAutoApprovedRequestThrowsInvalidStateTransition() {
        String id = createPending("req-auto", 0);

        assertThatThrownBy(() -> service.approve(id, "checker-1", "TRANSFER_CHECKER"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectTransitionsPendingToRejected() {
        String id = createPending("req-reject", 1);

        ApprovalRequestView view = service.reject(id, "checker-1", "TRANSFER_CHECKER");

        assertThat(view.state()).isEqualTo(ApprovalState.REJECTED);
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        String id = createPending("req-cancel", 1);

        ApprovalRequestView view = service.cancel(id, "maker-1");

        assertThat(view.state()).isEqualTo(ApprovalState.CANCELLED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests ApprovalCommandServiceApproveTest`
Expected: FAIL — `approve`/`reject`/`cancel` do not exist.

- [ ] **Step 3: Implement the exceptions**

`approval-engine/src/main/java/com/visionbank/approval/service/ConcurrentStateChangeException.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;

public class ConcurrentStateChangeException extends RuntimeException {
    public final String requestId;
    public final ApprovalState currentState;

    public ConcurrentStateChangeException(String requestId, ApprovalState currentState) {
        super("Request " + requestId + " already moved to " + currentState);
        this.requestId = requestId;
        this.currentState = currentState;
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/service/InvalidStateTransitionException.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;

public class InvalidStateTransitionException extends RuntimeException {
    public final String requestId;
    public final ApprovalState currentState;
    public final String requestedAction;

    public InvalidStateTransitionException(String requestId, ApprovalState currentState, String requestedAction) {
        super("Action " + requestedAction + " is never valid from " + currentState + " (request " + requestId + ")");
        this.requestId = requestId;
        this.currentState = currentState;
        this.requestedAction = requestedAction;
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/service/ForbiddenActionException.java`:
```java
package com.visionbank.approval.service;

public class ForbiddenActionException extends RuntimeException {
    public ForbiddenActionException(String message) {
        super(message);
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/service/ApprovalRequestNotFoundException.java`:
```java
package com.visionbank.approval.service;

public class ApprovalRequestNotFoundException extends RuntimeException {
    public ApprovalRequestNotFoundException(String requestId) {
        super("No approval request with id " + requestId);
    }
}
```

- [ ] **Step 4: Add `approve`, `reject`, `cancel`, and the shared race-classification helper to `ApprovalCommandService`**

Add these fields/methods to `approval-engine/src/main/java/com/visionbank/approval/service/ApprovalCommandService.java` (constructor already has `requests`, `decisions`, `audits`, `outbox`, `workflow`, `guards`):

```java
    @Transactional
    public ApprovalRequestView approve(String requestId, String actorId, String actorRole) {
        ApprovalRequest request = loadOrThrow(requestId);

        if (request.getState() != ApprovalState.PENDING_APPROVAL) {
            throw classifyRaceOrIllegal(requestId, request.getState(), "approve");
        }

        GuardContext eligibility = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0,
                actorId, actorRole, false);
        if (guards.get("actor_is_maker").evaluate(eligibility) && !request.getPolicySnapshot().makerCanApprove()) {
            throw new ForbiddenActionException("Maker cannot approve their own request: " + requestId);
        }
        if (!guards.get("actor_is_eligible_checker").evaluate(eligibility)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        if (decisions.existsByRequestIdAndActorId(requestId, actorId)) {
            return toView(request); // already decided — idempotent replay of the decision itself
        }

        ApprovalDecision decision = new ApprovalDecision();
        decision.setRequestId(requestId);
        decision.setActorId(actorId);
        decision.setActorRole(actorRole);
        decision.setDecision(ApprovalDecision.DecisionType.APPROVE);
        decision.setCreatedAt(Instant.now());
        decisions.save(decision);

        long approvalCount = decisions.countByRequestIdAndDecision(requestId, ApprovalDecision.DecisionType.APPROVE);
        GuardContext quorumCtx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), approvalCount,
                actorId, actorRole, false);

        if (!guards.get("approvals_satisfied").evaluate(quorumCtx)) {
            writeAudit(requestId, actorId, actorRole, "APPROVAL_RECORDED", ApprovalState.PENDING_APPROVAL, ApprovalState.PENDING_APPROVAL);
            return toView(request);
        }

        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, request.getVersion(), ApprovalState.APPROVED);
        if (rows == 0) {
            ApprovalState current = requests.findByRequestId(requestId).orElseThrow().getState();
            throw classifyRaceOrIllegal(requestId, current, "approve");
        }

        writeAudit(requestId, actorId, actorRole, "APPROVED", ApprovalState.PENDING_APPROVAL, ApprovalState.APPROVED);
        writeOutbox(requestId, "ApprovalApproved");
        return new ApprovalRequestView(requestId, ApprovalState.APPROVED, request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView reject(String requestId, String actorId, String actorRole) {
        ApprovalRequest request = loadOrThrow(requestId);
        if (request.getState() != ApprovalState.PENDING_APPROVAL) {
            throw classifyRaceOrIllegal(requestId, request.getState(), "reject");
        }
        GuardContext ctx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0, actorId, actorRole, false);
        if (!guards.get("actor_is_eligible_checker").evaluate(ctx)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, request.getVersion(), ApprovalState.REJECTED);
        if (rows == 0) {
            ApprovalState current = requests.findByRequestId(requestId).orElseThrow().getState();
            throw classifyRaceOrIllegal(requestId, current, "reject");
        }
        writeAudit(requestId, actorId, actorRole, "REJECTED", ApprovalState.PENDING_APPROVAL, ApprovalState.REJECTED);
        writeOutbox(requestId, "ApprovalRejected");
        return new ApprovalRequestView(requestId, ApprovalState.REJECTED, request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView cancel(String requestId, String actorId) {
        ApprovalRequest request = loadOrThrow(requestId);
        if (request.getState() != ApprovalState.PENDING_APPROVAL) {
            throw classifyRaceOrIllegal(requestId, request.getState(), "cancel");
        }
        GuardContext ctx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0, actorId, "MAKER", false);
        if (!guards.get("actor_is_maker").evaluate(ctx)) {
            throw new ForbiddenActionException("Only the maker can cancel request " + requestId);
        }

        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, request.getVersion(), ApprovalState.CANCELLED);
        if (rows == 0) {
            ApprovalState current = requests.findByRequestId(requestId).orElseThrow().getState();
            throw classifyRaceOrIllegal(requestId, current, "cancel");
        }
        writeAudit(requestId, actorId, "MAKER", "CANCELLED", ApprovalState.PENDING_APPROVAL, ApprovalState.CANCELLED);
        writeOutbox(requestId, "ApprovalCancelled");
        return new ApprovalRequestView(requestId, ApprovalState.CANCELLED, request.getVersion() + 1);
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
     * A current state is a "lost race" (409 CONCURRENT_STATE_CHANGE) if it's reachable
     * from PENDING_APPROVAL per the workflow definition — some other legal command won.
     * Otherwise the action was never legal from this state, regardless of timing
     * (409 INVALID_STATE_TRANSITION) — e.g. approve() on a request that auto-approved
     * straight from SUBMITTED and never passed through PENDING_APPROVAL.
     */
    private RuntimeException classifyRaceOrIllegal(String requestId, ApprovalState current, String action) {
        boolean reachableFromPendingApproval = workflow.transitionsFrom(ApprovalState.PENDING_APPROVAL).stream()
                .anyMatch(t -> t.to() == current);
        if (current == ApprovalState.PENDING_APPROVAL || reachableFromPendingApproval) {
            return new ConcurrentStateChangeException(requestId, current);
        }
        return new InvalidStateTransitionException(requestId, current, action);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests ApprovalCommandServiceApproveTest`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): approve/reject/cancel with OCC, N-of-M quorum, 409 differentiation"
```

---

### Task 6: Concurrency tests — two checkers racing, cancel-vs-approve racing

**Files:**
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/ApprovalConcurrencyTest.java`

**Interfaces:**
- Consumes: `ApprovalCommandService.create/approve/cancel` (Tasks 4-5), `ApprovalRequestRepository`, `ApprovalDecisionRepository` (Task 3). Produces nothing new — this task is pure verification of Task 5's invariants under real concurrent load.

- [ ] **Step 1: Write the failing (well, not-yet-existing) concurrency tests**

`approval-engine/src/test/java/com/visionbank/approval/service/ApprovalConcurrencyTest.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalDecision;
import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.repository.ApprovalDecisionRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ApprovalConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalCommandService service;
    @Autowired ApprovalRequestRepository requests;
    @Autowired ApprovalDecisionRepository decisions;

    private String createPendingRequiredOne(String requestId) {
        service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
                new PolicySnapshot("v1", 1, List.of("TRANSFER_CHECKER"), false),
                "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
        return requestId;
    }

    private String createPendingRequiredTwo(String requestId) {
        service.create(new CreateApprovalRequest(requestId, "TRANSFER_APPROVAL", "maker-1",
                new PolicySnapshot("v1", 2, List.of("TRANSFER_CHECKER"), false),
                "{}", Instant.now().plusSeconds(86400)), UUID.randomUUID().toString());
        return requestId;
    }

    @Test
    void twoCheckersSatisfyingQuorumSimultaneously_bothRecordedAndTransitionHappensExactlyOnce() throws Exception {
        // Regression test for the undercounting race: without the row lock in
        // loadOrThrow, both checkers can each count only their own decision
        // (count=1 < required=2), both skip the transition, and the request
        // gets stuck in PENDING_APPROVAL forever despite quorum being met.
        String id = createPendingRequiredTwo("race-quorum");
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Object> checkerA = raceTask(startGate, () ->
                service.approve(id, "checker-A", "TRANSFER_CHECKER"));
        Callable<Object> checkerB = raceTask(startGate, () ->
                service.approve(id, "checker-B", "TRANSFER_CHECKER"));

        Future<Object> futureA = pool.submit(checkerA);
        Future<Object> futureB = pool.submit(checkerB);
        startGate.countDown();

        Object outcomeA = resolve(futureA);
        Object outcomeB = resolve(futureB);
        pool.shutdown();

        // Both approvals are legitimate — quorum requires exactly these two —
        // so neither should be rejected as a lost race.
        assertThat(outcomeA).isInstanceOf(ApprovalRequestView.class);
        assertThat(outcomeB).isInstanceOf(ApprovalRequestView.class);
        assertThat(requests.findByRequestId(id).get().getState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(decisions.countByRequestIdAndDecision(id, ApprovalDecision.DecisionType.APPROVE)).isEqualTo(2);
    }

    @Test
    void twoCheckersApprovingSimultaneously_exactlyOneWins() throws Exception {
        String id = createPendingRequiredOne("race-checkers");
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Object> checkerA = raceTask(startGate, () ->
                service.approve(id, "checker-A", "TRANSFER_CHECKER"));
        Callable<Object> checkerB = raceTask(startGate, () ->
                service.approve(id, "checker-B", "TRANSFER_CHECKER"));

        Future<Object> futureA = pool.submit(checkerA);
        Future<Object> futureB = pool.submit(checkerB);
        startGate.countDown();

        List<Object> outcomes = List.of(resolve(futureA), resolve(futureB));
        pool.shutdown();

        long successes = outcomes.stream().filter(o -> o instanceof ApprovalRequestView).count();
        long conflicts = outcomes.stream().filter(o -> o instanceof ConcurrentStateChangeException).count();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(requests.findByRequestId(id).get().getState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(decisions.countByRequestIdAndDecision(id, ApprovalDecision.DecisionType.APPROVE)).isEqualTo(1);
    }

    @Test
    void cancelVersusApprove_exactlyOneWins() throws Exception {
        String id = createPendingRequiredOne("race-cancel-approve");
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Object> makerCancel = raceTask(startGate, () ->
                service.cancel(id, "maker-1"));
        Callable<Object> checkerApprove = raceTask(startGate, () ->
                service.approve(id, "checker-A", "TRANSFER_CHECKER"));

        Future<Object> futureCancel = pool.submit(makerCancel);
        Future<Object> futureApprove = pool.submit(checkerApprove);
        startGate.countDown();

        List<Object> outcomes = List.of(resolve(futureCancel), resolve(futureApprove));
        pool.shutdown();

        long successes = outcomes.stream().filter(o -> o instanceof ApprovalRequestView).count();
        long conflicts = outcomes.stream().filter(o -> o instanceof ConcurrentStateChangeException).count();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        ApprovalState finalState = requests.findByRequestId(id).get().getState();
        assertThat(finalState).isIn(ApprovalState.CANCELLED, ApprovalState.APPROVED);
    }

    private Callable<Object> raceTask(CountDownLatch startGate, Callable<Object> action) {
        return () -> {
            startGate.await();
            try {
                return action.call();
            } catch (ConcurrentStateChangeException e) {
                return e;
            }
        };
    }

    private Object resolve(Future<Object> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
```

- [ ] **Step 2: Run test to verify current behavior**

Run: `cd approval-engine && ./gradlew test --tests ApprovalConcurrencyTest`
Expected: PASS (3 tests) — Task 5's `findByRequestIdForUpdate` row lock plus `guardedTransition` together provide the race safety; this task exists to prove it under real thread concurrency, not to add new production code. The quorum test (`twoCheckersSatisfyingQuorumSimultaneously...`) is the one that would have failed before the row-lock fix — both checkers would have returned `PENDING_APPROVAL` and the assertion on final state `APPROVED` would fail, with 0 rather than 2 decisions transitioning it. If any test is flaky or the quorum test's final state isn't `APPROVED`, stop and re-examine Task 5's `loadOrThrow`/`findByRequestIdForUpdate` — the lock must be acquired before the decision count, not after.

- [ ] **Step 3: Commit**

```bash
git add approval-engine/
git commit -m "test(approval-engine): verify concurrent approval races resolve to exactly one winner"
```

---

### Task 7: Outbox relay — polls unpublished events, HTTP-pushes to Transfer, retries on failure

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/OutboxClaimService.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/OutboxRelay.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/OutboxRelayTest.java`

**Interfaces:**
- Consumes: `OutboxEventRepository.selectAndLockUnpublishedIds`/`markClaimed` (Task 3, updated above); property `transfer-service.webhook-url` (Task 1 `application.yml`).
- Produces: `OutboxRelay.relayOnce() -> int` (count of events published this pass) — called on a `@Scheduled` fixed delay in production, called directly in tests for determinism. Internally claims a batch (`FOR UPDATE SKIP LOCKED`, no HTTP call under the lock) before publishing, so concurrent relay instances never double-send the same row in the same pass — redelivery can still happen across passes (crash after publish, before `markPublished`), which `processed_event` on the consumer already handles.

- [ ] **Step 1: Write the failing test using WireMock as the Transfer-side stand-in**

Add to `approval-engine/build.gradle.kts` dependencies:
```kotlin
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
```

`approval-engine/src/test/java/com/visionbank/approval/service/OutboxRelayTest.java`:
```java
package com.visionbank.approval.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class OutboxRelayTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer wireMock = new WireMockServer(9091);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("transfer-service.webhook-url", () -> "http://localhost:9091/internal/events");
    }

    @Autowired OutboxRelay relay;
    @Autowired OutboxEventRepository outbox;

    @BeforeEach
    void startWireMock() {
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.resetAll();
        wireMock.stop();
    }

    private OutboxEvent unpublishedEvent(String requestId) {
        OutboxEvent event = new OutboxEvent();
        event.setRequestId(requestId);
        event.setEventType("ApprovalApproved");
        event.setEventVersion(1);
        event.setPayload("{\"requestId\":\"" + requestId + "\"}");
        event.setCreatedAt(Instant.now());
        return outbox.save(event);
    }

    @Test
    void publishesUnpublishedEventAndMarksItPublished() {
        wireMock.stubFor(post(urlEqualTo("/internal/events")).willReturn(ok()));
        OutboxEvent event = unpublishedEvent("relay-1");

        int published = relay.relayOnce();

        assertThat(published).isGreaterThanOrEqualTo(1);
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/events")));
        assertThat(outbox.findById(event.getEventId()).get().getPublishedAt()).isNotNull();
    }

    @Test
    void leavesEventUnpublishedWhenTransferServiceIsDown() {
        wireMock.stubFor(post(urlEqualTo("/internal/events")).willReturn(serverError()));
        OutboxEvent event = unpublishedEvent("relay-2");

        relay.relayOnce();

        assertThat(outbox.findById(event.getEventId()).get().getPublishedAt()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests OutboxRelayTest`
Expected: FAIL — `OutboxRelay` does not exist.

- [ ] **Step 3: Implement the claim service and the relay as two separate beans**

Split deliberately in two: `OutboxRelay.relayOnce()` orchestrates and makes the HTTP call; `OutboxClaimService` owns both transactional units. If `claimBatch`/`markPublished` lived on `OutboxRelay` itself and `relayOnce()` called `this.claimBatch()`, Spring's proxy-based AOP would never see that call — `@Transactional` on a self-invoked method is silently a no-op, each repository call would get its own ad-hoc transaction instead, and the `FOR UPDATE SKIP LOCKED` lock from `selectAndLockUnpublishedIds` would release the instant that single query returned — before `markClaimed` ran, defeating the whole claim mechanism from Task 3. Calling through a *different* injected bean goes through the real proxy, so this only works because it's two classes.

`approval-engine/src/main/java/com/visionbank/approval/service/OutboxClaimService.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxClaimService {

    private static final Duration STALE_CLAIM_AFTER = Duration.ofSeconds(30);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outbox;

    public OutboxClaimService(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    // Locks, claims, and releases the row lock within one short transaction —
    // no HTTP call happens while any row is locked. Safe for more than one
    // relay instance to run this concurrently: FOR UPDATE SKIP LOCKED means
    // two instances never claim the same row in the same pass.
    @Transactional
    public List<OutboxEvent> claimBatch() {
        List<String> ids = outbox.selectAndLockUnpublishedIds(Instant.now().minus(STALE_CLAIM_AFTER), BATCH_SIZE);
        if (ids.isEmpty()) {
            return List.of();
        }
        outbox.markClaimed(ids, Instant.now());
        return outbox.findAllById(ids);
    }

    @Transactional
    public void markPublished(String eventId) {
        outbox.findById(eventId).ifPresent(e -> {
            e.setPublishedAt(Instant.now());
            outbox.save(e);
        });
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/service/OutboxRelay.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxClaimService claimService;
    private final RestClient restClient;
    private final String webhookUrl;

    public OutboxRelay(OutboxClaimService claimService,
                        @Value("${transfer-service.webhook-url}") String webhookUrl) {
        this.claimService = claimService;
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.create();
    }

    @Scheduled(fixedDelay = 2000)
    public int relayOnce() {
        List<OutboxEvent> claimed = claimService.claimBatch();
        int published = 0;
        for (OutboxEvent event : claimed) {
            if (publish(event)) {
                claimService.markPublished(event.getEventId());
                published++;
            }
            // On failure, claimedAt stays set — it becomes reclaimable once
            // it's older than the claim service's stale-claim window, so a
            // crash mid-publish doesn't strand the event forever.
        }
        return published;
    }

    private boolean publish(OutboxEvent event) {
        try {
            HttpStatusCode status = restClient.post()
                    .uri(webhookUrl)
                    .header("X-Event-Id", event.getEventId())
                    .header("X-Event-Type", event.getEventType())
                    .body(event.getPayload())
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return status.is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Failed to relay event {} ({}): {}", event.getEventId(), event.getEventType(), e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests OutboxRelayTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): outbox relay with at-least-once HTTP delivery"
```

---

### Task 8: Expiry sweeper (per-row guarded transitions) + expiry-vs-approve race test

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/ExpiryTransitionService.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/service/ExpirySweeper.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/ExpirySweeperTest.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/service/ExpiryVersusApproveConcurrencyTest.java`

**Interfaces:**
- Consumes: `ApprovalRequestRepository.findByStateAndExpiresAtBefore` / `guardedTransition` (Task 3); `ApprovalCommandService.approve` (Task 5); `AuditLogRepository`, `OutboxEventRepository` (Task 3).
- Produces: `ExpirySweeper.sweepOnce() -> int` (count expired this pass).

- [ ] **Step 1: Write the failing sweeper test**

`approval-engine/src/test/java/com/visionbank/approval/service/ExpirySweeperTest.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.PolicySnapshot;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ExpirySweeperTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ExpirySweeper sweeper;
    @Autowired ApprovalRequestRepository requests;

    private ApprovalRequest stalePendingRequest(String id) {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId(id);
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState(ApprovalState.PENDING_APPROVAL);
        r.setVersion(1L);
        r.setMakerId("maker-1");
        r.setPolicySnapshot(new PolicySnapshot("v1", 1, java.util.List.of("TRANSFER_CHECKER"), false));
        r.setPayload("{}");
        r.setCreatedAt(Instant.now().minusSeconds(90000));
        r.setExpiresAt(Instant.now().minusSeconds(3600));
        return requests.save(r);
    }

    @Test
    void sweeperExpiresStalePendingRequest() {
        stalePendingRequest("expire-1");

        int expired = sweeper.sweepOnce();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(requests.findByRequestId("expire-1").get().getState()).isEqualTo(ApprovalState.EXPIRED);
    }

    @Test
    void sweeperIgnoresRequestsNotYetExpired() {
        ApprovalRequest fresh = stalePendingRequest("expire-2");
        fresh.setExpiresAt(Instant.now().plusSeconds(3600));
        requests.save(fresh);

        sweeper.sweepOnce();

        assertThat(requests.findByRequestId("expire-2").get().getState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests ExpirySweeperTest`
Expected: FAIL — `ExpirySweeper` does not exist.

- [ ] **Step 3: Implement the transition service and the sweeper as two separate beans**

Same self-invocation hazard as Task 7: if `sweepOnce()` called `this.expireOne(...)` within the same class, `@Transactional` on `expireOne` would be silently inert, breaking the atomicity of guardedTransition+audit+outbox that spec §13/§15 require. Splitting into `ExpiryTransitionService` (the transactional unit) and `ExpirySweeper` (the loop, calling through the real proxy) fixes it the same way `OutboxClaimService`/`OutboxRelay` did in Task 7.

`approval-engine/src/main/java/com/visionbank/approval/service/ExpiryTransitionService.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.AuditLog;
import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.repository.AuditLogRepository;
import com.visionbank.approval.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ExpiryTransitionService {

    private final ApprovalRequestRepository requests;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;

    public ExpiryTransitionService(ApprovalRequestRepository requests, AuditLogRepository audits, OutboxEventRepository outbox) {
        this.requests = requests;
        this.audits = audits;
        this.outbox = outbox;
    }

    // Each candidate goes through the SAME guarded update as every other transition —
    // never a bulk UPDATE — so an in-flight approve() and this sweep can't both "win".
    @Transactional
    public boolean expireOne(String requestId, long expectedVersion) {
        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, expectedVersion, ApprovalState.EXPIRED);
        if (rows == 0) {
            return false; // lost the race to a concurrent approve/reject/cancel — not an error
        }
        AuditLog log = new AuditLog();
        log.setRequestId(requestId);
        log.setAction("EXPIRED");
        log.setPreviousState(ApprovalState.PENDING_APPROVAL);
        log.setNewState(ApprovalState.EXPIRED);
        log.setCreatedAt(Instant.now());
        audits.save(log);

        OutboxEvent event = new OutboxEvent();
        event.setRequestId(requestId);
        event.setEventType("ApprovalExpired");
        event.setEventVersion(1);
        event.setPayload("{\"requestId\":\"" + requestId + "\"}");
        event.setCreatedAt(Instant.now());
        outbox.save(event);
        return true;
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/service/ExpirySweeper.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ExpirySweeper {

    private final ApprovalRequestRepository requests;
    private final ExpiryTransitionService transitionService;

    public ExpirySweeper(ApprovalRequestRepository requests, ExpiryTransitionService transitionService) {
        this.requests = requests;
        this.transitionService = transitionService;
    }

    @Scheduled(fixedDelay = 60000)
    public int sweepOnce() {
        List<ApprovalRequest> candidates = requests.findByStateAndExpiresAtBefore(ApprovalState.PENDING_APPROVAL, Instant.now());
        int expiredCount = 0;
        for (ApprovalRequest candidate : candidates) {
            if (transitionService.expireOne(candidate.getRequestId(), candidate.getVersion())) {
                expiredCount++;
            }
        }
        return expiredCount;
    }

    // Thin delegator so existing/prior test call sites (sweeper.expireOne(...))
    // still exercise the real transactional bean rather than a self-invoked no-op.
    public boolean expireOne(String requestId, long expectedVersion) {
        return transitionService.expireOne(requestId, expectedVersion);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests ExpirySweeperTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Write and run the expiry-vs-approve race test**

`approval-engine/src/test/java/com/visionbank/approval/service/ExpiryVersusApproveConcurrencyTest.java`:
```java
package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.PolicySnapshot;
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
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ExpiryVersusApproveConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalRequestRepository requests;
    @Autowired ApprovalCommandService service;
    @Autowired ExpirySweeper sweeper;

    @Test
    void approveVersusExpire_exactlyOneWins() throws Exception {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId("race-expire");
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState(ApprovalState.PENDING_APPROVAL);
        r.setVersion(1L);
        r.setMakerId("maker-1");
        r.setPolicySnapshot(new PolicySnapshot("v1", 1, List.of("TRANSFER_CHECKER"), false));
        r.setPayload("{}");
        r.setCreatedAt(Instant.now().minusSeconds(90000));
        r.setExpiresAt(Instant.now().minusSeconds(1));
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

        ApprovalState finalState = requests.findByRequestId("race-expire").get().getState();
        assertThat(finalState).isIn(ApprovalState.EXPIRED, ApprovalState.APPROVED);
    }
}
```

Run: `cd approval-engine && ./gradlew test --tests ExpiryVersusApproveConcurrencyTest`
Expected: PASS (1 test).

- [ ] **Step 6: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): expiry sweeper on the guarded per-row transition path"
```

---

### Task 9: REST controller, DTOs, and 409/403/404 error mapping

**Files:**
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/ApprovalController.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/CreateApprovalRequestDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/ActorCommandDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/ApprovalResponseDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/dto/ErrorResponseDto.java`
- Create: `approval-engine/src/main/java/com/visionbank/approval/web/ApiExceptionHandler.java`
- Test: `approval-engine/src/test/java/com/visionbank/approval/web/ApprovalControllerTest.java`

**Interfaces:**
- Consumes: `ApprovalCommandService` (Tasks 4-5, `approve`/`reject`/`cancel` now take no `idempotencyKey`), `ConcurrentStateChangeException`/`InvalidStateTransitionException`/`ForbiddenActionException`/`ApprovalRequestNotFoundException`/`IdempotencyConflictException` (Tasks 4-5).
- Produces: `POST /approvals` (create, requires `Idempotency-Key`), `POST /approvals/{id}/approve|reject|cancel` (no `Idempotency-Key` header — decision-level idempotency per spec §11), `GET /approvals/{id}` — this is the public contract Transfer Service's `ApprovalEngineClient` (Task 15) is written against.

- [ ] **Step 1: Write the DTOs and the failing controller test (happy path + one 409 per spec §19)**

`approval-engine/src/main/java/com/visionbank/approval/web/dto/CreateApprovalRequestDto.java`:
```java
package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record CreateApprovalRequestDto(
        @NotBlank String requestId,
        @NotBlank String requestType,
        @NotBlank String makerId,
        @NotNull Integer requiredApprovals,
        @NotNull List<String> eligibleRoles,
        boolean makerCanApprove,
        @NotBlank String payloadJson,
        @NotNull Instant expiresAt) {}
```

`approval-engine/src/main/java/com/visionbank/approval/web/dto/ActorCommandDto.java`:
```java
package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ActorCommandDto(@NotBlank String actorId, String actorRole) {}
```

`approval-engine/src/main/java/com/visionbank/approval/web/dto/ApprovalResponseDto.java`:
```java
package com.visionbank.approval.web.dto;

import com.visionbank.approval.domain.ApprovalState;

public record ApprovalResponseDto(String requestId, ApprovalState state, long version) {}
```

`approval-engine/src/main/java/com/visionbank/approval/web/dto/ErrorResponseDto.java`:
```java
package com.visionbank.approval.web.dto;

public record ErrorResponseDto(String code, String requestId, String currentState, String requestedAction) {}
```

`approval-engine/src/test/java/com/visionbank/approval/web/ApprovalControllerTest.java`:
```java
package com.visionbank.approval.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionbank.approval.web.dto.CreateApprovalRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    private String createDto(String requestId, int required) throws Exception {
        return mapper.writeValueAsString(new CreateApprovalRequestDto(
                requestId, "TRANSFER_APPROVAL", "maker-1", required, List.of("TRANSFER_CHECKER"),
                false, "{}", Instant.now().plusSeconds(86400)));
    }

    @Test
    void createReturns200WithPendingApprovalState() throws Exception {
        mockMvc.perform(post("/approvals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(createDto("ctrl-1", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PENDING_APPROVAL")));
    }

    @Test
    void approveOnAlreadyApprovedRequestReturns409WithConcurrentStateChangeCode() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-2", 1)));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                .contentType("application/json")
                .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-1", "TRANSFER_CHECKER"))));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-2", "TRANSFER_CHECKER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_STATE_CHANGE")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd approval-engine && ./gradlew test --tests ApprovalControllerTest`
Expected: FAIL — `ApprovalController` does not exist.

- [ ] **Step 3: Implement the controller and exception handler**

`approval-engine/src/main/java/com/visionbank/approval/web/ApprovalController.java`:
```java
package com.visionbank.approval.web;

import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.service.*;
import com.visionbank.approval.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalCommandService service;

    public ApprovalController(ApprovalCommandService service) {
        this.service = service;
    }

    @PostMapping
    public ApprovalResponseDto create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody CreateApprovalRequestDto dto) {
        PolicySnapshot policy = new PolicySnapshot("v1", dto.requiredApprovals(), dto.eligibleRoles(), dto.makerCanApprove());
        CreateApprovalRequest cmd = new CreateApprovalRequest(dto.requestId(), dto.requestType(), dto.makerId(),
                policy, dto.payloadJson(), dto.expiresAt());
        ApprovalRequestView view = service.create(cmd, idempotencyKey);
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    // No Idempotency-Key header on approve/reject/cancel — decisions are
    // naturally idempotent per (request_id, actor_id) via Task 3's unique
    // constraint (spec §11). Only create() originates a request and needs
    // client-supplied replay protection.

    @PostMapping("/{id}/approve")
    public ApprovalResponseDto approve(@PathVariable String id,
                                        @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.approve(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponseDto reject(@PathVariable String id,
                                       @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.reject(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/cancel")
    public ApprovalResponseDto cancel(@PathVariable String id,
                                       @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.cancel(id, dto.actorId());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }
}
```

`approval-engine/src/main/java/com/visionbank/approval/web/ApiExceptionHandler.java`:
```java
package com.visionbank.approval.web;

import com.visionbank.approval.service.*;
import com.visionbank.approval.web.dto.ErrorResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ConcurrentStateChangeException.class)
    public ResponseEntity<ErrorResponseDto> handle(ConcurrentStateChangeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponseDto("CONCURRENT_STATE_CHANGE", e.requestId, e.currentState.name(), null));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponseDto> handle(InvalidStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponseDto("INVALID_STATE_TRANSITION", e.requestId, e.currentState.name(), e.requestedAction));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponseDto> handle(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponseDto("IDEMPOTENCY_CONFLICT", null, null, null));
    }

    @ExceptionHandler(ForbiddenActionException.class)
    public ResponseEntity<ErrorResponseDto> handle(ForbiddenActionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponseDto("FORBIDDEN_ACTION", null, null, null));
    }

    @ExceptionHandler(ApprovalRequestNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handle(ApprovalRequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponseDto("NOT_FOUND", null, null, null));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd approval-engine && ./gradlew test --tests ApprovalControllerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the full Approval Engine test suite**

Run: `cd approval-engine && ./gradlew test`
Expected: PASS — all tests from Tasks 1-9.

- [ ] **Step 6: Commit**

```bash
git add approval-engine/
git commit -m "feat(approval-engine): REST controller and error mapping for the two 409 codes"
```

---

## Part B — Transfer Service

### Task 10: Transfer Service scaffold + domain entities + `PolicyResolver`

**Files:**
- Create: `transfer-service/settings.gradle.kts`
- Create: `transfer-service/build.gradle.kts`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/TransferServiceApplication.java`
- Create: `transfer-service/src/main/resources/application.yml`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/domain/TransferState.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/domain/Transfer.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/domain/ProcessedEvent.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/repository/TransferRepository.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/repository/ProcessedEventRepository.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/policy/ApprovalPolicy.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/policy/PolicyResolver.java`
- Test: `transfer-service/src/test/java/com/visionbank/transfer/policy/PolicyResolverTest.java`

**Interfaces:**
- Produces: `TransferState` enum (`CREATED, VALIDATED, WAITING_FOR_APPROVAL, RELEASE_PENDING, RELEASED, REJECTED, CANCELLED, EXPIRED`); `ApprovalPolicy(int requiredApprovals, List<String> eligibleRoles, boolean makerCanApprove)`; `PolicyResolver.resolve(long amountMinorUnits) -> ApprovalPolicy`. Task 12 consumes `PolicyResolver.resolve`.

- [ ] **Step 1: Create the Gradle scaffold**

`transfer-service/settings.gradle.kts`:
```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "transfer-service"
```

`transfer-service/build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.visionbank"
version = "0.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.postgresql:postgresql")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
}

tasks.withType<Test> { useJUnitPlatform() }
```

`transfer-service/src/main/java/com/visionbank/transfer/TransferServiceApplication.java`:
```java
package com.visionbank.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TransferServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}
```

`transfer-service/src/main/resources/application.yml`:
```yaml
server:
  port: 8080
spring:
  application:
    name: transfer-service
  datasource:
    url: jdbc:postgresql://localhost:5432/transfer
    username: transfer
    password: transfer
  jpa:
    hibernate:
      ddl-auto: update
approval-engine:
  base-url: http://localhost:8081
policy:
  auto-release-ceiling-minor-units: 500000      # < 5,000.00 -> 0 approvals
  single-checker-ceiling-minor-units: 5000000   # < 50,000.00 -> 1 approver, else 2
```

Generate the real Gradle wrapper now, same as Task 1 (see its Global Constraints amendment and Step 1 for why 8.14.3, not 8.11):
```bash
cd transfer-service && gradle wrapper --gradle-version 8.14.3 && chmod +x gradlew && cd ..
```
Commit `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` with this task's other files.

- [ ] **Step 2: Write domain types and the failing `PolicyResolver` test**

`transfer-service/src/main/java/com/visionbank/transfer/domain/TransferState.java`:
```java
package com.visionbank.transfer.domain;

public enum TransferState {
    CREATED, VALIDATED, WAITING_FOR_APPROVAL, RELEASE_PENDING, RELEASED, REJECTED, CANCELLED, EXPIRED
}
```

`transfer-service/src/main/java/com/visionbank/transfer/policy/ApprovalPolicy.java`:
```java
package com.visionbank.transfer.policy;

import java.util.List;

public record ApprovalPolicy(int requiredApprovals, List<String> eligibleRoles, boolean makerCanApprove) {}
```

`transfer-service/src/test/java/com/visionbank/transfer/policy/PolicyResolverTest.java`:
```java
package com.visionbank.transfer.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyResolverTest {

    private final PolicyResolver resolver = new PolicyResolver(500000L, 5000000L);

    @Test
    void belowAutoReleaseCeilingRequiresNoApprovals() {
        ApprovalPolicy policy = resolver.resolve(100000L);
        assertThat(policy.requiredApprovals()).isEqualTo(0);
    }

    @Test
    void betweenCeilingsRequiresOneApproval() {
        ApprovalPolicy policy = resolver.resolve(1000000L);
        assertThat(policy.requiredApprovals()).isEqualTo(1);
    }

    @Test
    void atOrAboveSingleCheckerCeilingRequiresTwoApprovals() {
        ApprovalPolicy policy = resolver.resolve(5000000L);
        assertThat(policy.requiredApprovals()).isEqualTo(2);
    }

    @Test
    void makerCanNeverApproveUnderThisPolicy() {
        assertThat(resolver.resolve(1000000L).makerCanApprove()).isFalse();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd transfer-service && ./gradlew test --tests PolicyResolverTest`
Expected: FAIL — `PolicyResolver` does not exist.

- [ ] **Step 4: Implement `PolicyResolver`, `Transfer`, `ProcessedEvent`, and their repositories**

`transfer-service/src/main/java/com/visionbank/transfer/policy/PolicyResolver.java`:
```java
package com.visionbank.transfer.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PolicyResolver {

    private final long autoReleaseCeiling;
    private final long singleCheckerCeiling;

    public PolicyResolver(@Value("${policy.auto-release-ceiling-minor-units}") long autoReleaseCeiling,
                           @Value("${policy.single-checker-ceiling-minor-units}") long singleCheckerCeiling) {
        this.autoReleaseCeiling = autoReleaseCeiling;
        this.singleCheckerCeiling = singleCheckerCeiling;
    }

    public ApprovalPolicy resolve(long amountMinorUnits) {
        if (amountMinorUnits < autoReleaseCeiling) {
            return new ApprovalPolicy(0, List.of(), false);
        }
        if (amountMinorUnits < singleCheckerCeiling) {
            return new ApprovalPolicy(1, List.of("TRANSFER_CHECKER"), false);
        }
        return new ApprovalPolicy(2, List.of("TRANSFER_CHECKER"), false);
    }
}
```

`transfer-service/src/main/java/com/visionbank/transfer/domain/Transfer.java`:
```java
package com.visionbank.transfer.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "transfer")
@Getter
@Setter
public class Transfer {

    @Id
    @Column(name = "transfer_id")
    private String transferId;

    @Column(name = "maker_id", nullable = false)
    private String makerId;

    @Column(name = "from_account", nullable = false)
    private String fromAccount;

    @Column(name = "to_account", nullable = false)
    private String toAccount;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private TransferState state;

    @Column(name = "approval_request_id")
    private String approvalRequestId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    // Persisted once at submission and reused on any retry (Task 13) — never
    // recomputed with Instant.now() again, or a retry's engine call would
    // carry a different expiresAt than the original, which the engine's
    // idempotency hash would see as a body mismatch (spurious 409).
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

`transfer-service/src/main/java/com/visionbank/transfer/domain/ProcessedEvent.java`:
```java
package com.visionbank.transfer.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "processed_event")
@Getter
@Setter
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
```

`transfer-service/src/main/java/com/visionbank/transfer/repository/TransferRepository.java`:
```java
package com.visionbank.transfer.repository;

import com.visionbank.transfer.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
    Optional<Transfer> findByApprovalRequestId(String approvalRequestId);
}
```

`transfer-service/src/main/java/com/visionbank/transfer/repository/ProcessedEventRepository.java`:
```java
package com.visionbank.transfer.repository;

import com.visionbank.transfer.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd transfer-service && ./gradlew test --tests PolicyResolverTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add transfer-service/
git commit -m "feat(transfer-service): scaffold, domain entities, threshold-based policy resolver"
```

---

### Task 11: `CoreBankingClient` interface + in-memory stub (idempotent release, balance/limit/duplicate checks)

**Files:**
- Create: `transfer-service/src/main/java/com/visionbank/transfer/corebanking/CoreBankingClient.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/corebanking/ValidationResult.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/corebanking/StubCoreBankingClient.java`
- Test: `transfer-service/src/test/java/com/visionbank/transfer/corebanking/StubCoreBankingClientTest.java`

**Interfaces:**
- Produces: `CoreBankingClient.validate(String fromAccount, long amountMinorUnits, String duplicateKey) -> ValidationResult`; `CoreBankingClient.release(String transferId, String fromAccount, long amountMinorUnits) -> boolean` (idempotent — same `transferId` never moves money twice); `ValidationResult(boolean sufficientBalance, boolean withinLimit, boolean duplicate)`. Task 12 consumes `validate`; Task 13 consumes `release`.

- [ ] **Step 1: Write the failing test**

`transfer-service/src/test/java/com/visionbank/transfer/corebanking/StubCoreBankingClientTest.java`:
```java
package com.visionbank.transfer.corebanking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StubCoreBankingClientTest {

    private final StubCoreBankingClient client = new StubCoreBankingClient();

    @Test
    void validateReturnsSufficientBalanceForFundedAccount() {
        ValidationResult result = client.validate("ACC-FUNDED", 100_00L, "dup-key-1");
        assertThat(result.sufficientBalance()).isTrue();
        assertThat(result.withinLimit()).isTrue();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void validateFlagsInsufficientBalanceOverStubCeiling() {
        ValidationResult result = client.validate("ACC-FUNDED", 999_999_999_00L, "dup-key-2");
        assertThat(result.sufficientBalance()).isFalse();
    }

    @Test
    void validateFlagsRepeatedDuplicateKeyOnSecondCall() {
        client.validate("ACC-FUNDED", 100_00L, "dup-key-3");
        ValidationResult second = client.validate("ACC-FUNDED", 100_00L, "dup-key-3");
        assertThat(second.duplicate()).isTrue();
    }

    @Test
    void releaseIsIdempotent_secondCallForSameTransferDoesNotMoveMoneyAgain() {
        boolean first = client.release("transfer-1", "ACC-FUNDED", 100_00L);
        boolean second = client.release("transfer-1", "ACC-FUNDED", 100_00L);

        assertThat(first).isTrue();
        assertThat(second).isTrue(); // idempotent success, not a second movement
        assertThat(client.releaseCountFor("transfer-1")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd transfer-service && ./gradlew test --tests StubCoreBankingClientTest`
Expected: FAIL — `CoreBankingClient`/`StubCoreBankingClient` do not exist.

- [ ] **Step 3: Implement the interface and stub**

`transfer-service/src/main/java/com/visionbank/transfer/corebanking/ValidationResult.java`:
```java
package com.visionbank.transfer.corebanking;

public record ValidationResult(boolean sufficientBalance, boolean withinLimit, boolean duplicate) {
    public boolean isValid() {
        return sufficientBalance && withinLimit && !duplicate;
    }
}
```

`transfer-service/src/main/java/com/visionbank/transfer/corebanking/CoreBankingClient.java`:
```java
package com.visionbank.transfer.corebanking;

public interface CoreBankingClient {
    ValidationResult validate(String fromAccount, long amountMinorUnits, String duplicateKey);

    /**
     * Idempotent by transferId: a redelivered ApprovalApproved event, or a
     * retry after a lost response, must never move money twice for the same
     * transferId. This is a core-banking contract, not a Transfer Service
     * concern — ReleaseService relies on it without re-implementing dedup.
     */
    boolean release(String transferId, String fromAccount, long amountMinorUnits);
}
```

`transfer-service/src/main/java/com/visionbank/transfer/corebanking/StubCoreBankingClient.java`:
```java
package com.visionbank.transfer.corebanking;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake core banking, allowed per the assignment ("core banking ... can be
 * stubbed or mocked"). Not a networked service — a bean inside this app.
 * A real implementation would swap this for an HTTP/gRPC client behind the
 * same CoreBankingClient interface.
 */
@Component
public class StubCoreBankingClient implements CoreBankingClient {

    private static final long STUB_BALANCE_CEILING = 100_000_00L;
    private static final long STUB_LIMIT_CEILING = 500_000_00L;

    private final Set<String> seenDuplicateKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> releaseCounts = new ConcurrentHashMap<>();

    @Override
    public ValidationResult validate(String fromAccount, long amountMinorUnits, String duplicateKey) {
        boolean sufficientBalance = amountMinorUnits <= STUB_BALANCE_CEILING;
        boolean withinLimit = amountMinorUnits <= STUB_LIMIT_CEILING;
        boolean duplicate = !seenDuplicateKeys.add(duplicateKey);
        return new ValidationResult(sufficientBalance, withinLimit, duplicate);
    }

    @Override
    public synchronized boolean release(String transferId, String fromAccount, long amountMinorUnits) {
        // Fulfills the interface's idempotent-by-transferId contract: a second
        // call for a transferId already released is a no-op, not a second movement.
        releaseCounts.computeIfAbsent(transferId, id -> new AtomicInteger(0)).compareAndSet(0, 1);
        return true;
    }

    public int releaseCountFor(String transferId) {
        AtomicInteger count = releaseCounts.get(transferId);
        return count == null ? 0 : count.get();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd transfer-service && ./gradlew test --tests StubCoreBankingClientTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add transfer-service/
git commit -m "feat(transfer-service): core banking client interface with idempotent stub"
```

---

### Task 12: `ApprovalEngineClient` — outbound REST client to the Approval Engine

**Files:**
- Create: `transfer-service/src/main/java/com/visionbank/transfer/approval/ApprovalEngineClient.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/approval/CreateWorkflowRequest.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/approval/WorkflowResponse.java`
- Test: `transfer-service/src/test/java/com/visionbank/transfer/approval/ApprovalEngineClientTest.java`

**Interfaces:**
- Consumes: `ApprovalPolicy` (Task 10).
- Produces: `ApprovalEngineClient.createWorkflow(CreateWorkflowRequest req, String idempotencyKey) -> WorkflowResponse`; `CreateWorkflowRequest(String requestId, String requestType, String makerId, ApprovalPolicy policy, String payloadJson, Instant expiresAt)`; `WorkflowResponse(String requestId, String state, long version)`. Task 13 consumes `createWorkflow`.

- [ ] **Step 1: Write the failing test against a WireMock stand-in for the Approval Engine**

`transfer-service/src/test/java/com/visionbank/transfer/approval/ApprovalEngineClientTest.java`:
```java
package com.visionbank.transfer.approval;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.transfer.policy.ApprovalPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class ApprovalEngineClientTest {

    WireMockServer wireMock = new WireMockServer(9092);
    ApprovalEngineClient client;

    @BeforeEach
    void setUp() {
        wireMock.start();
        client = new ApprovalEngineClient("http://localhost:9092");
    }

    @AfterEach
    void tearDown() {
        wireMock.resetAll();
        wireMock.stop();
    }

    @Test
    void createWorkflowPostsToApprovalsAndParsesResponse() {
        wireMock.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"req-1\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

        CreateWorkflowRequest req = new CreateWorkflowRequest("req-1", "TRANSFER_APPROVAL", "maker-1",
                new ApprovalPolicy(1, List.of("TRANSFER_CHECKER"), false), "{}", Instant.now().plusSeconds(86400));

        WorkflowResponse response = client.createWorkflow(req, UUID.randomUUID().toString());

        assertThat(response.requestId()).isEqualTo("req-1");
        assertThat(response.state()).isEqualTo("PENDING_APPROVAL");
        wireMock.verify(postRequestedFor(urlEqualTo("/approvals")).withHeader("Idempotency-Key", matching(".+")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd transfer-service && ./gradlew test --tests ApprovalEngineClientTest`
Expected: FAIL — `ApprovalEngineClient` does not exist.

- [ ] **Step 3: Implement the client**

`transfer-service/src/main/java/com/visionbank/transfer/approval/CreateWorkflowRequest.java`:
```java
package com.visionbank.transfer.approval;

import com.visionbank.transfer.policy.ApprovalPolicy;

import java.time.Instant;

public record CreateWorkflowRequest(
        String requestId, String requestType, String makerId,
        ApprovalPolicy policy, String payloadJson, Instant expiresAt) {}
```

`transfer-service/src/main/java/com/visionbank/transfer/approval/WorkflowResponse.java`:
```java
package com.visionbank.transfer.approval;

public record WorkflowResponse(String requestId, String state, long version) {}
```

`transfer-service/src/main/java/com/visionbank/transfer/approval/ApprovalEngineClient.java`:
```java
package com.visionbank.transfer.approval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class ApprovalEngineClient {

    private final RestClient restClient;

    public ApprovalEngineClient(@Value("${approval-engine.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public WorkflowResponse createWorkflow(CreateWorkflowRequest req, String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "requestId", req.requestId(),
                "requestType", req.requestType(),
                "makerId", req.makerId(),
                "requiredApprovals", req.policy().requiredApprovals(),
                "eligibleRoles", req.policy().eligibleRoles(),
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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd transfer-service && ./gradlew test --tests ApprovalEngineClientTest`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add transfer-service/
git commit -m "feat(transfer-service): approval engine REST client"
```

---

### Task 13: `TransferSubmissionService` — validation + crash-safe two-phase submission + workflow creation

**Files:**
- Create: `transfer-service/src/main/java/com/visionbank/transfer/service/TransferPersistenceService.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/service/TransferSubmissionService.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/service/SubmitTransferCommand.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/service/TransferView.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/service/ValidationFailedException.java`
- Test: `transfer-service/src/test/java/com/visionbank/transfer/service/TransferSubmissionServiceTest.java`

**Interfaces:**
- Consumes: `TransferRepository` (Task 10, `Transfer` now has `expiresAt`), `CoreBankingClient` (Task 11), `PolicyResolver` (Task 10), `ApprovalEngineClient.createWorkflow` (Task 12).
- Produces: `TransferSubmissionService.submit(SubmitTransferCommand cmd, String idempotencyKey) -> TransferView`; `SubmitTransferCommand(String makerId, String fromAccount, String toAccount, long amountMinorUnits, String currency)`; `TransferView(String transferId, TransferState state)`; `TransferPersistenceService.persistCreated(...)`/`markWaitingForApproval(...)`. Task 15 (controller) and Task 14 (event listener, via `TransferRepository`) consume `Transfer` rows this produces.

**Design notes enforced by this task (both from post-review fixes):**
1. Regardless of whether the engine's `createWorkflow` call returns `APPROVED` (auto-release) or `PENDING_APPROVAL`, the persisted `Transfer.state` ends at `WAITING_FOR_APPROVAL` and release is **never** triggered here — release only ever happens in Task 14, driven by the `ApprovalApproved` event (spec §16, §20 "convergence").
2. **The engine's HTTP call must never sit inside an open local DB transaction.** The original version wrapped the whole method in `@Transactional`, so a crash between the (already-committed-on-the-engine-side) HTTP call and the local commit stranded the transfer with no local record, *and* corrupted the stub's in-memory `seenDuplicateKeys` (already added on the doomed attempt), permanently blocking any retry of that idempotency key. Fixed by splitting into two commit points on `TransferPersistenceService` (a separate bean — same self-invocation reasoning as Tasks 7/8): persist `CREATED` first (validation happens once, before this), call the engine, then persist `WAITING_FOR_APPROVAL`. A retry with the same `Idempotency-Key` that lands after `CREATED` but before completion **resumes** using the *already-persisted* `transferId` and `expiresAt` — it does not re-validate (so the stub's one-shot duplicate check is never touched twice) and does not recompute `expiresAt` (recomputing it would change the engine's idempotency hash and turn a legitimate retry into a spurious `409 IDEMPOTENCY_CONFLICT`).

- [ ] **Step 1: Write the failing tests, including the crash-resume case**

`transfer-service/src/test/java/com/visionbank/transfer/service/TransferSubmissionServiceTest.java`:
```java
package com.visionbank.transfer.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class TransferSubmissionServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer engineStub = new WireMockServer(9092);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("approval-engine.base-url", () -> "http://localhost:9092");
    }

    @Autowired TransferSubmissionService service;
    @Autowired TransferPersistenceService persistenceService;
    @Autowired TransferRepository transfers;

    @BeforeEach
    void startStub() {
        engineStub.start();
    }

    @AfterEach
    void stopStub() {
        engineStub.resetAll();
        engineStub.stop();
    }

    private SubmitTransferCommand smallTransfer() {
        return new SubmitTransferCommand("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");
    }

    @Test
    void engineReturningApprovedStillLeavesTransferWaitingForApproval() {
        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"whatever\",\"state\":\"APPROVED\",\"version\":1}")));

        TransferView view = service.submit(smallTransfer(), UUID.randomUUID().toString());

        assertThat(transfers.findById(view.transferId()).get().getState()).isEqualTo(TransferState.WAITING_FOR_APPROVAL);
    }

    @Test
    void engineReturningPendingApprovalLeavesTransferWaitingForApproval() {
        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"whatever\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

        TransferView view = service.submit(smallTransfer(), UUID.randomUUID().toString());

        assertThat(transfers.findById(view.transferId()).get().getState()).isEqualTo(TransferState.WAITING_FOR_APPROVAL);
    }

    @Test
    void insufficientBalanceFailsValidationBeforeCallingEngine() {
        SubmitTransferCommand huge = new SubmitTransferCommand("maker-1", "ACC-FUNDED", "ACC-DEST", 999_999_999_00L, "AED");

        assertThatThrownBy(() -> service.submit(huge, UUID.randomUUID().toString()))
                .isInstanceOf(ValidationFailedException.class);

        engineStub.verify(0, postRequestedFor(urlEqualTo("/approvals")));
    }

    @Test
    void replayingSameIdempotencyKeyReturnsSameTransferWithoutSecondEngineCall() {
        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"whatever\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));
        String key = UUID.randomUUID().toString();

        TransferView first = service.submit(smallTransfer(), key);
        TransferView second = service.submit(smallTransfer(), key);

        assertThat(second.transferId()).isEqualTo(first.transferId());
        engineStub.verify(1, postRequestedFor(urlEqualTo("/approvals")));
    }

    @Test
    void resumingAfterCrashReusesThePersistedTransferIdAndExpiresAtWithoutReValidating() {
        // Simulates a crash after persistCreated() committed but before the
        // engine call/markWaitingForApproval completed: pre-create the CREATED
        // row directly via the persistence service, with a fixed expiresAt.
        Instant fixedExpiresAt = Instant.parse("2030-01-01T00:00:00Z");
        Transfer created = persistenceService.persistCreated("resume-1", smallTransfer(), "resume-key", fixedExpiresAt);
        assertThat(created.getState()).isEqualTo(TransferState.CREATED);

        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"resume-1\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

        TransferView view = service.submit(smallTransfer(), "resume-key");

        assertThat(view.transferId()).isEqualTo("resume-1");
        assertThat(transfers.findById("resume-1").get().getState()).isEqualTo(TransferState.WAITING_FOR_APPROVAL);
        engineStub.verify(1, postRequestedFor(urlEqualTo("/approvals"))
                .withRequestBody(containing("2030-01-01T00:00:00Z")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd transfer-service && ./gradlew test --tests TransferSubmissionServiceTest`
Expected: FAIL — `TransferSubmissionService`/`TransferPersistenceService` do not exist.

- [ ] **Step 3: Implement the DTOs and the two services**

`transfer-service/src/main/java/com/visionbank/transfer/service/SubmitTransferCommand.java`:
```java
package com.visionbank.transfer.service;

public record SubmitTransferCommand(String makerId, String fromAccount, String toAccount, long amountMinorUnits, String currency) {}
```

`transfer-service/src/main/java/com/visionbank/transfer/service/TransferView.java`:
```java
package com.visionbank.transfer.service;

import com.visionbank.transfer.domain.TransferState;

public record TransferView(String transferId, TransferState state) {}
```

`transfer-service/src/main/java/com/visionbank/transfer/service/ValidationFailedException.java`:
```java
package com.visionbank.transfer.service;

public class ValidationFailedException extends RuntimeException {
    public ValidationFailedException(String reason) {
        super(reason);
    }
}
```

`transfer-service/src/main/java/com/visionbank/transfer/service/TransferPersistenceService.java`:
```java
package com.visionbank.transfer.service;

import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Owns the two commit points TransferSubmissionService.submit() needs on
 * either side of the (non-transactional) Approval Engine HTTP call. Kept on
 * a separate bean rather than as methods called via `this.` on the
 * submission service — same self-invocation reasoning as OutboxClaimService
 * (Task 7) and ExpiryTransitionService (Task 8).
 */
@Service
public class TransferPersistenceService {

    private final TransferRepository transfers;

    public TransferPersistenceService(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Transactional
    public Transfer persistCreated(String transferId, SubmitTransferCommand cmd, String idempotencyKey, Instant expiresAt) {
        Transfer transfer = new Transfer();
        transfer.setTransferId(transferId);
        transfer.setMakerId(cmd.makerId());
        transfer.setFromAccount(cmd.fromAccount());
        transfer.setToAccount(cmd.toAccount());
        transfer.setAmountMinorUnits(cmd.amountMinorUnits());
        transfer.setCurrency(cmd.currency());
        transfer.setState(TransferState.CREATED);
        transfer.setIdempotencyKey(idempotencyKey);
        transfer.setExpiresAt(expiresAt);
        transfer.setCreatedAt(Instant.now());
        return transfers.save(transfer);
    }

    @Transactional
    public Transfer markWaitingForApproval(String transferId, String approvalRequestId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow();
        transfer.setApprovalRequestId(approvalRequestId);
        transfer.setState(TransferState.WAITING_FOR_APPROVAL);
        return transfers.save(transfer);
    }
}
```

`transfer-service/src/main/java/com/visionbank/transfer/service/TransferSubmissionService.java`:
```java
package com.visionbank.transfer.service;

import com.visionbank.transfer.approval.ApprovalEngineClient;
import com.visionbank.transfer.approval.CreateWorkflowRequest;
import com.visionbank.transfer.approval.WorkflowResponse;
import com.visionbank.transfer.corebanking.CoreBankingClient;
import com.visionbank.transfer.corebanking.ValidationResult;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.policy.ApprovalPolicy;
import com.visionbank.transfer.policy.PolicyResolver;
import com.visionbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Deliberately NOT @Transactional — this method spans an external HTTP call
// to the Approval Engine, which must never sit inside an open DB transaction.
// TransferPersistenceService owns the two actual commit points.
@Service
public class TransferSubmissionService {

    private final TransferRepository transfers;
    private final CoreBankingClient coreBanking;
    private final PolicyResolver policyResolver;
    private final ApprovalEngineClient approvalEngineClient;
    private final TransferPersistenceService persistenceService;

    public TransferSubmissionService(TransferRepository transfers, CoreBankingClient coreBanking,
                                      PolicyResolver policyResolver, ApprovalEngineClient approvalEngineClient,
                                      TransferPersistenceService persistenceService) {
        this.transfers = transfers;
        this.coreBanking = coreBanking;
        this.policyResolver = policyResolver;
        this.approvalEngineClient = approvalEngineClient;
        this.persistenceService = persistenceService;
    }

    public TransferView submit(SubmitTransferCommand cmd, String idempotencyKey) {
        Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transfer t = existing.get();
            if (t.getApprovalRequestId() != null) {
                return new TransferView(t.getTransferId(), t.getState()); // fully completed already
            }
            return completeWorkflowCreation(t, cmd); // resume: same transferId, same persisted expiresAt
        }

        ValidationResult validation = coreBanking.validate(cmd.fromAccount(), cmd.amountMinorUnits(), idempotencyKey);
        if (!validation.isValid()) {
            throw new ValidationFailedException(
                    "sufficientBalance=" + validation.sufficientBalance()
                    + " withinLimit=" + validation.withinLimit()
                    + " duplicate=" + validation.duplicate());
        }

        String transferId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(86400);
        Transfer created = persistenceService.persistCreated(transferId, cmd, idempotencyKey, expiresAt);

        return completeWorkflowCreation(created, cmd);
    }

    private TransferView completeWorkflowCreation(Transfer transfer, SubmitTransferCommand cmd) {
        ApprovalPolicy policy = policyResolver.resolve(cmd.amountMinorUnits());
        CreateWorkflowRequest workflowRequest = new CreateWorkflowRequest(
                transfer.getTransferId(), "TRANSFER_APPROVAL", cmd.makerId(), policy,
                "{\"transferId\":\"" + transfer.getTransferId() + "\",\"amount\":" + cmd.amountMinorUnits() + "}",
                transfer.getExpiresAt()); // persisted value — never recomputed on retry
        WorkflowResponse workflowResponse = approvalEngineClient.createWorkflow(workflowRequest, transfer.getTransferId());

        // Always WAITING_FOR_APPROVAL here regardless of workflowResponse.state() —
        // release is only ever triggered by consuming ApprovalApproved (Task 14),
        // so auto-release and N-approver release share one trigger path.
        Transfer completed = persistenceService.markWaitingForApproval(transfer.getTransferId(), workflowResponse.requestId());
        return new TransferView(completed.getTransferId(), completed.getState());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd transfer-service && ./gradlew test --tests TransferSubmissionServiceTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add transfer-service/
git commit -m "feat(transfer-service): crash-safe two-phase submission with reused expiresAt on resume"
```

---

### Task 14: `ReleaseService` + `ApprovalEventListener` — idempotent event consumption and the single release trigger

**Files:**
- Create: `transfer-service/src/main/java/com/visionbank/transfer/service/ReleaseService.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/approval/ApprovalEventListener.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/approval/IncomingEvent.java`
- Test: `transfer-service/src/test/java/com/visionbank/transfer/approval/ApprovalEventListenerTest.java`
- Test: `transfer-service/src/test/java/com/visionbank/transfer/ConvergenceTest.java`

**Interfaces:**
- Consumes: `TransferRepository`, `ProcessedEventRepository` (Task 10), `CoreBankingClient.release` (Task 11).
- Produces: `ReleaseService.release(Transfer transfer) -> void` (idempotent — no-op if already `RELEASED`); `ApprovalEventListener.handle(IncomingEvent event) -> void`; `IncomingEvent(String eventId, String eventType, String requestId)`. Task 15 (webhook controller) consumes `ApprovalEventListener.handle`.

- [ ] **Step 1: Write the failing listener test**

`transfer-service/src/main/java/com/visionbank/transfer/approval/IncomingEvent.java`:
```java
package com.visionbank.transfer.approval;

public record IncomingEvent(String eventId, String eventType, String requestId) {}
```

`transfer-service/src/test/java/com/visionbank/transfer/approval/ApprovalEventListenerTest.java`:
```java
package com.visionbank.transfer.approval;

import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.ProcessedEventRepository;
import com.visionbank.transfer.repository.TransferRepository;
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
class ApprovalEventListenerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalEventListener listener;
    @Autowired TransferRepository transfers;
    @Autowired ProcessedEventRepository processedEvents;

    private Transfer waitingTransfer(String transferId, String approvalRequestId) {
        Transfer t = new Transfer();
        t.setTransferId(transferId);
        t.setMakerId("maker-1");
        t.setFromAccount("ACC-FUNDED");
        t.setToAccount("ACC-DEST");
        t.setAmountMinorUnits(1000_00L);
        t.setCurrency("AED");
        t.setState(TransferState.WAITING_FOR_APPROVAL);
        t.setApprovalRequestId(approvalRequestId);
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setExpiresAt(Instant.now().plusSeconds(86400));
        t.setCreatedAt(Instant.now());
        return transfers.save(t);
    }

    @Test
    void approvalApprovedEventReleasesTheTransfer() {
        waitingTransfer("t-1", "req-1");

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "req-1"));

        assertThat(transfers.findById("t-1").get().getState()).isEqualTo(TransferState.RELEASED);
    }

    @Test
    void approvalRejectedEventMarksTransferRejected() {
        waitingTransfer("t-2", "req-2");

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalRejected", "req-2"));

        assertThat(transfers.findById("t-2").get().getState()).isEqualTo(TransferState.REJECTED);
    }

    @Test
    void duplicateEventDeliveryIsANoOp() {
        waitingTransfer("t-3", "req-3");
        String eventId = UUID.randomUUID().toString();

        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "req-3"));
        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "req-3")); // redelivery, same eventId

        assertThat(transfers.findById("t-3").get().getState()).isEqualTo(TransferState.RELEASED);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd transfer-service && ./gradlew test --tests ApprovalEventListenerTest`
Expected: FAIL — `ApprovalEventListener`/`ReleaseService` do not exist.

- [ ] **Step 3: Implement `ReleaseService` and `ApprovalEventListener`**

`transfer-service/src/main/java/com/visionbank/transfer/service/ReleaseService.java`:
```java
package com.visionbank.transfer.service;

import com.visionbank.transfer.corebanking.CoreBankingClient;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

@Service
public class ReleaseService {

    private final CoreBankingClient coreBanking;
    private final TransferRepository transfers;

    public ReleaseService(CoreBankingClient coreBanking, TransferRepository transfers) {
        this.coreBanking = coreBanking;
        this.transfers = transfers;
    }

    public void release(Transfer transfer) {
        if (transfer.getState() == TransferState.RELEASED) {
            return; // idempotent no-op — already released
        }
        transfer.setState(TransferState.RELEASE_PENDING);
        transfers.save(transfer);

        boolean success = coreBanking.release(transfer.getTransferId(), transfer.getFromAccount(), transfer.getAmountMinorUnits());
        if (success) {
            transfer.setState(TransferState.RELEASED);
            transfers.save(transfer);
        }
        // On failure the transfer stays RELEASE_PENDING; a retry scheduler polling
        // RELEASE_PENDING rows would call release() again — out of scope for this
        // exercise since the stub always succeeds, but the state exists for it.
    }
}
```

`transfer-service/src/main/java/com/visionbank/transfer/approval/ApprovalEventListener.java`:
```java
package com.visionbank.transfer.approval;

import com.visionbank.transfer.domain.ProcessedEvent;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.ProcessedEventRepository;
import com.visionbank.transfer.repository.TransferRepository;
import com.visionbank.transfer.service.ReleaseService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class ApprovalEventListener {

    private final TransferRepository transfers;
    private final ProcessedEventRepository processedEvents;
    private final ReleaseService releaseService;

    public ApprovalEventListener(TransferRepository transfers, ProcessedEventRepository processedEvents,
                                  ReleaseService releaseService) {
        this.transfers = transfers;
        this.processedEvents = processedEvents;
        this.releaseService = releaseService;
    }

    @Transactional
    public void handle(IncomingEvent event) {
        if (processedEvents.existsById(event.eventId())) {
            return; // at-least-once delivery — redelivery of an already-processed event is a no-op
        }

        transfers.findByApprovalRequestId(event.requestId()).ifPresent(transfer -> {
            switch (event.eventType()) {
                case "ApprovalApproved" -> releaseService.release(transfer);
                case "ApprovalRejected" -> setState(transfer, TransferState.REJECTED);
                case "ApprovalCancelled" -> setState(transfer, TransferState.CANCELLED);
                case "ApprovalExpired" -> setState(transfer, TransferState.EXPIRED);
                case "ApprovalSubmitted" -> { /* no-op — transfer already WAITING_FOR_APPROVAL */ }
                default -> { /* unknown event type — ignore rather than fail the whole delivery */ }
            }
        });

        ProcessedEvent processed = new ProcessedEvent();
        processed.setEventId(event.eventId());
        processed.setProcessedAt(Instant.now());
        processedEvents.save(processed);
    }

    private void setState(Transfer transfer, TransferState state) {
        transfer.setState(state);
        transfers.save(transfer);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd transfer-service && ./gradlew test --tests ApprovalEventListenerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Write and run the convergence test**

`transfer-service/src/test/java/com/visionbank/transfer/ConvergenceTest.java`:
```java
package com.visionbank.transfer;

import com.visionbank.transfer.approval.ApprovalEventListener;
import com.visionbank.transfer.approval.IncomingEvent;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.TransferRepository;
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

/**
 * Proves spec §16/§20: whether a transfer was auto-released (0 approvals) or
 * released after N checkers, the ONLY code path that releases it is consuming
 * ApprovalApproved — there is no separate auto-release branch to drift out of sync.
 */
@Testcontainers
@SpringBootTest
class ConvergenceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalEventListener listener;
    @Autowired TransferRepository transfers;

    private Transfer waitingTransfer(String transferId, String approvalRequestId) {
        Transfer t = new Transfer();
        t.setTransferId(transferId);
        t.setMakerId("maker-1");
        t.setFromAccount("ACC-FUNDED");
        t.setToAccount("ACC-DEST");
        t.setAmountMinorUnits(1000_00L);
        t.setCurrency("AED");
        t.setState(TransferState.WAITING_FOR_APPROVAL);
        t.setApprovalRequestId(approvalRequestId);
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setExpiresAt(Instant.now().plusSeconds(86400));
        t.setCreatedAt(Instant.now());
        return transfers.save(t);
    }

    @Test
    void autoApprovedAndMultiApproverPathsBothReleaseViaTheSameEvent() {
        waitingTransfer("t-auto", "req-auto");
        waitingTransfer("t-multi", "req-multi");

        // "auto" path: engine emitted ApprovalSubmitted then ApprovalApproved immediately on create
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalSubmitted", "req-auto"));
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "req-auto"));

        // "multi" path: engine emitted ApprovalSubmitted at create, ApprovalApproved only after quorum
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalSubmitted", "req-multi"));
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "req-multi"));

        assertThat(transfers.findById("t-auto").get().getState()).isEqualTo(TransferState.RELEASED);
        assertThat(transfers.findById("t-multi").get().getState()).isEqualTo(TransferState.RELEASED);
    }
}
```

Run: `cd transfer-service && ./gradlew test --tests ConvergenceTest`
Expected: PASS (1 test).

- [ ] **Step 6: Commit**

```bash
git add transfer-service/
git commit -m "feat(transfer-service): idempotent event consumption with single release trigger path"
```

---

### Task 15: Transfer REST controller + event webhook controller

**Files:**
- Create: `transfer-service/src/main/java/com/visionbank/transfer/web/TransferController.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/web/EventWebhookController.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/web/dto/SubmitTransferDto.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/web/dto/TransferResponseDto.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/web/dto/IncomingEventDto.java`
- Create: `transfer-service/src/main/java/com/visionbank/transfer/web/ApiExceptionHandler.java`
- Test: `transfer-service/src/test/java/com/visionbank/transfer/web/TransferControllerTest.java`

**Interfaces:**
- Consumes: `TransferSubmissionService` (Task 13), `ApprovalEventListener` (Task 14).
- Produces: `POST /transfers` (submit), `GET /transfers/{id}`, `POST /internal/events` — this is the `webhookUrl` Task 7's `OutboxRelay` posts to and Task 16's docker-compose wires via `transfer-service.webhook-url` / `TRANSFER_WEBHOOK_URL`.

- [ ] **Step 1: Write the DTOs and the failing controller test**

`transfer-service/src/main/java/com/visionbank/transfer/web/dto/SubmitTransferDto.java`:
```java
package com.visionbank.transfer.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record SubmitTransferDto(
        @NotBlank String makerId,
        @NotBlank String fromAccount,
        @NotBlank String toAccount,
        @Positive long amountMinorUnits,
        @NotBlank String currency) {}
```

`transfer-service/src/main/java/com/visionbank/transfer/web/dto/TransferResponseDto.java`:
```java
package com.visionbank.transfer.web.dto;

import com.visionbank.transfer.domain.TransferState;

public record TransferResponseDto(String transferId, TransferState state) {}
```

`transfer-service/src/main/java/com/visionbank/transfer/web/dto/IncomingEventDto.java`:
```java
package com.visionbank.transfer.web.dto;

public record IncomingEventDto(String requestId) {}
```

`transfer-service/src/test/java/com/visionbank/transfer/web/TransferControllerTest.java`:
```java
package com.visionbank.transfer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.transfer.web.dto.SubmitTransferDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer engineStub = new WireMockServer(9092);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("approval-engine.base-url", () -> "http://localhost:9092");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void startStub() {
        engineStub.start();
        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"req-ctrl\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));
    }

    @AfterEach
    void stopStub() {
        engineStub.resetAll();
        engineStub.stop();
    }

    @Test
    void submitReturnsWaitingForApproval() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("WAITING_FOR_APPROVAL")));
    }

    @Test
    void submitWithInsufficientBalanceReturns422() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 999_999_999_00L, "AED");

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd transfer-service && ./gradlew test --tests TransferControllerTest`
Expected: FAIL — `TransferController` does not exist.

- [ ] **Step 3: Implement the controllers and exception handler**

`transfer-service/src/main/java/com/visionbank/transfer/web/TransferController.java`:
```java
package com.visionbank.transfer.web;

import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.repository.TransferRepository;
import com.visionbank.transfer.service.SubmitTransferCommand;
import com.visionbank.transfer.service.TransferSubmissionService;
import com.visionbank.transfer.service.TransferView;
import com.visionbank.transfer.web.dto.SubmitTransferDto;
import com.visionbank.transfer.web.dto.TransferResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferSubmissionService submissionService;
    private final TransferRepository transfers;

    public TransferController(TransferSubmissionService submissionService, TransferRepository transfers) {
        this.submissionService = submissionService;
        this.transfers = transfers;
    }

    @PostMapping
    public TransferResponseDto submit(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody SubmitTransferDto dto) {
        SubmitTransferCommand cmd = new SubmitTransferCommand(dto.makerId(), dto.fromAccount(), dto.toAccount(),
                dto.amountMinorUnits(), dto.currency());
        TransferView view = submissionService.submit(cmd, idempotencyKey);
        return new TransferResponseDto(view.transferId(), view.state());
    }

    @GetMapping("/{id}")
    public TransferResponseDto get(@PathVariable String id) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No transfer " + id));
        return new TransferResponseDto(t.getTransferId(), t.getState());
    }
}
```

`transfer-service/src/main/java/com/visionbank/transfer/web/EventWebhookController.java`:
```java
package com.visionbank.transfer.web;

import com.visionbank.transfer.approval.ApprovalEventListener;
import com.visionbank.transfer.approval.IncomingEvent;
import com.visionbank.transfer.web.dto.IncomingEventDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/events")
public class EventWebhookController {

    private final ApprovalEventListener listener;

    public EventWebhookController(ApprovalEventListener listener) {
        this.listener = listener;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Event-Id") String eventId,
                                         @RequestHeader("X-Event-Type") String eventType,
                                         @RequestBody IncomingEventDto body) {
        listener.handle(new IncomingEvent(eventId, eventType, body.requestId()));
        return ResponseEntity.ok().build();
    }
}
```

`transfer-service/src/main/java/com/visionbank/transfer/web/ApiExceptionHandler.java`:
```java
package com.visionbank.transfer.web;

import com.visionbank.transfer.service.ValidationFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<String> handle(ValidationFailedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd transfer-service && ./gradlew test --tests TransferControllerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the full Transfer Service test suite**

Run: `cd transfer-service && ./gradlew test`
Expected: PASS — all tests from Tasks 10-15.

- [ ] **Step 6: Commit**

```bash
git add transfer-service/
git commit -m "feat(transfer-service): transfer submission and event webhook controllers"
```

---

## Part C — Wiring, Deployment, Documentation

### Task 16: Dockerfiles + docker-compose + end-to-end smoke test

**Files:**
- Create: `approval-engine/Dockerfile`
- Create: `transfer-service/Dockerfile`
- Create: `docker-compose.yml`
- Create: `docker-compose.smoke-test.sh`

**Interfaces:**
- Consumes: Both services' `application.yml` port/env conventions (Tasks 1, 10); env var names `TRANSFER_WEBHOOK_URL`, `APPROVAL_ENGINE_BASE_URL`, `SPRING_DATASOURCE_*` override the `application.yml` defaults via Spring's standard env-var binding — no code change needed for this, just consistent property naming.

- [ ] **Step 1: Write both Dockerfiles**

`approval-engine/Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`transfer-service/Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Verify both wrappers exist (they were generated in Task 1 and Task 10, not here)**

```bash
ls approval-engine/gradlew approval-engine/gradle/wrapper/gradle-wrapper.jar
ls transfer-service/gradlew transfer-service/gradle/wrapper/gradle-wrapper.jar
```
Expected: all four paths exist and are tracked in git (`git ls-files` should list them). If either is missing, generate it now exactly as Task 1/10 specify (`gradle wrapper --gradle-version 8.14.3`) — don't use a different version here, the two services' wrappers must match.

- [ ] **Step 3: Write `docker-compose.yml`**

`docker-compose.yml`:
```yaml
services:
  postgres-transfer:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: transfer
      POSTGRES_USER: transfer
      POSTGRES_PASSWORD: transfer
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U transfer"]
      interval: 5s
      timeout: 3s
      retries: 10

  postgres-approval:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: approval
      POSTGRES_USER: approval
      POSTGRES_PASSWORD: approval
    ports: ["5433:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U approval"]
      interval: 5s
      timeout: 3s
      retries: 10

  approval-engine:
    build: ./approval-engine
    ports: ["8081:8081"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-approval:5432/approval?stringtype=unspecified
      SPRING_DATASOURCE_USERNAME: approval
      SPRING_DATASOURCE_PASSWORD: approval
      TRANSFER-SERVICE_WEBHOOK-URL: http://transfer-service:8080/internal/events
    depends_on:
      postgres-approval:
        condition: service_healthy

  transfer-service:
    build: ./transfer-service
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-transfer:5432/transfer
      SPRING_DATASOURCE_USERNAME: transfer
      SPRING_DATASOURCE_PASSWORD: transfer
      APPROVAL-ENGINE_BASE-URL: http://approval-engine:8081
    depends_on:
      postgres-transfer:
        condition: service_healthy
      approval-engine:
        condition: service_started
```

- [ ] **Step 4: Write and run the smoke test script**

`docker-compose.smoke-test.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for transfer-service to be reachable..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/transfers/does-not-exist -o /dev/null -w '%{http_code}' | grep -q 404; then
    break
  fi
  sleep 2
done

echo "Submitting an auto-release transfer (below policy threshold)..."
RESPONSE=$(curl -sf -X POST http://localhost:8080/transfers \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{"makerId":"maker-1","fromAccount":"ACC-FUNDED","toAccount":"ACC-DEST","amountMinorUnits":100000,"currency":"AED"}')
echo "Submit response: $RESPONSE"

TRANSFER_ID=$(echo "$RESPONSE" | grep -o '"transferId":"[^"]*"' | cut -d'"' -f4)

echo "Polling for release (outbox relay runs every 2s)..."
for i in $(seq 1 15); do
  STATE=$(curl -sf http://localhost:8080/transfers/"$TRANSFER_ID" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
  echo "  transfer state: $STATE"
  if [ "$STATE" = "RELEASED" ]; then
    echo "SMOKE TEST PASSED: transfer released end-to-end"
    exit 0
  fi
  sleep 2
done

echo "SMOKE TEST FAILED: transfer did not reach RELEASED"
exit 1
```

Run:
```bash
chmod +x docker-compose.smoke-test.sh
docker compose up --build -d
./docker-compose.smoke-test.sh
docker compose down
```
Expected: `SMOKE TEST PASSED: transfer released end-to-end`. If it fails, check `docker compose logs approval-engine transfer-service` — the most likely culprits are the two `SPRING_DATASOURCE_URL` values pointing at the wrong host (must be the compose service name, not `localhost`, since containers resolve each other by service name) or the outbox relay's `TRANSFER-SERVICE_WEBHOOK-URL` env var not matching Spring's relaxed-binding form of `transfer-service.webhook-url`.

- [ ] **Step 5: Commit**

```bash
git add approval-engine/Dockerfile transfer-service/Dockerfile docker-compose.yml docker-compose.smoke-test.sh
git commit -m "chore: dockerize both services and add end-to-end smoke test"
```
(Wrapper files were already committed in Task 1/10 — nothing new to add here for them.)

---

### Task 17: README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: nothing new — this documents Tasks 1-16 for a reader with no prior context.

- [ ] **Step 1: Write the README**

`README.md`:
```markdown
# Vision Bank Transfer Approval System

Maker-checker approval workflow for domestic fund transfers, built as two
independently deployable Spring Boot services. Full design record:
`docs/superpowers/specs/2026-08-25-transfer-approval-design.md`. Submission
HLD/LLD: `docs/hld.md`, `docs/lld.md`.

## Run it

```bash
docker compose up --build
```

Transfer Service: http://localhost:8080. Approval Engine: http://localhost:8081.

Submit a transfer:
```bash
curl -X POST http://localhost:8080/transfers \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"makerId":"maker-1","fromAccount":"ACC-FUNDED","toAccount":"ACC-DEST","amountMinorUnits":100000,"currency":"AED"}'
```
Amounts under 5,000.00 auto-release; 5,000–50,000 need 1 checker; 50,000+
need 2. See `docs/superpowers/specs/.../` §8 or `transfer-service`'s
`PolicyResolver` for the exact thresholds.

Run each service's tests independently:
```bash
cd approval-engine && ./gradlew test
cd transfer-service && ./gradlew test
```

## Key design decisions

- Two independent Spring Boot apps (Java 21, Spring Boot 4.1.x), one Postgres
  DB each — Transfer Domain owns what a transfer means, Approval Engine owns
  how an approval progresses.
- No message broker: lifecycle events move through a DB-backed transactional
  outbox, relayed via polling HTTP push, with idempotent consumption on the
  receiving side (`processed_event`).
- Every competing state transition — two checkers approving at once, a maker
  cancelling while a checker approves, an SLA expiry racing an approval —
  goes through one mechanism: a single guarded conditional `UPDATE ... WHERE
  state = ? AND version = ?`. Exactly one caller wins; the loser's entire
  transaction (decision, audit, outbox) rolls back.
- Core Banking is stubbed behind a `CoreBankingClient` interface inside
  Transfer Service, per the assignment's explicit allowance — not a third
  deployable service.
- [If the YAML tripwire from Task 1 was hit: "Workflow definition is a
  hardcoded transition table, not YAML-loaded — see Task 1 in the
  implementation plan for why."]

## What I'd do differently with more time

- A funds hold at submission (`CoreBankingClient.hold(...)`), consumed on
  release and freed on reject/cancel/expire — without it, two large
  concurrent transfers against the same account can each pass validation
  independently and both later release, overspending the real balance.
  Named here deliberately rather than silently omitted.
- Flyway migrations instead of `ddl-auto: update`.
- A retry scheduler polling `RELEASE_PENDING` transfers for core-banking
  failures (the state exists; the poller doesn't, since the stub never fails).
- Real authentication instead of trusting `actorId`/`actorRole` in request bodies.
- A second tenant on the Approval Engine to actually prove the workflow
  definition / policy snapshot / opaque envelope boundary generalizes,
  rather than asserting it from one tenant.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add README with run instructions and design trade-offs"
```

---

### Task 18: Submission HLD/LLD (`docs/hld.md`, `docs/lld.md`) — ≤4 pages combined

**Files:**
- Create: `docs/hld.md`
- Create: `docs/lld.md`

**Interfaces:**
- Consumes: the locked design spec (`docs/superpowers/specs/2026-08-25-transfer-approval-design.md`) and the implemented API contracts/entities from Tasks 3, 5, 9, 10, 13, 15 for exact field names.

This is the previously-approved 4-page skeleton, written out with three fixes already folded in (agreed during design review, not optional):

1. **Diagram weighting (HLD page 1):** the logical diagram — the two services, their DBs, Core Banking, sync/async arrows, i.e. what `docker-compose.yml` actually runs — is the dominant visual. Gateway/WAF/load-balancer/N-instances is a *single annotated sentence*, not a drawn component: *"Production would add: API Gateway/WAF, load balancing, N horizontally-scaled instances per service — omitted here; doesn't change the ownership or consistency model."*
2. **API contracts (LLD) carry full request/response JSON**, not just an endpoint list — pull the exact shapes from Task 9's `CreateApprovalRequestDto`/`ApprovalResponseDto`/`ErrorResponseDto` and Task 15's `SubmitTransferDto`/`TransferResponseDto`.
3. **The multi-approver sequence diagram IS the race diagram** — draw Checker A and Checker B firing concurrently, both hitting the guarded UPDATE, one getting `rows=1`/`APPROVED`, one getting `rows=0`/`409 CONCURRENT_STATE_CHANGE` — rather than a sequential happy path plus a separate abstract race flowchart.

- [ ] **Step 1: Write `docs/hld.md`**

Structure (spec section references in parens):
- **Thesis** (spec §1) — 2-3 sentences: what the system does, the ownership split.
- **Context/deployment diagram** — logical-diagram-dominant per fix #1 above. Consumers (corporate banking users) → Transfer Service → Approval Engine, with Core Banking as a stubbed dependency behind `CoreBankingClient` (spec §4), sync REST arrows and async outbox-relay arrows both shown (spec §5).
- **Ownership table** (spec §4).
- **Communication + failure behavior table** (spec §5) — the four flows and what happens if the other side is down.
- **Consistency statement**: "Strong/transactional within each service; eventually consistent across the boundary — no distributed transaction. The outbox is the seam that makes partial failure safe." Add the one-sentence asymmetric-resilience note from spec §5 here too — it's cheap and it's exactly the kind of NFR precision the HLD rubric line rewards.
- **NFRs**: idempotency (spec §11), concurrency (spec §12, one sentence pointing at the LLD page for the mechanism), partial failure (spec §17).
- **Named gap**: one sentence on the funds-hold gap (spec §5/§21) — state it, don't let a reviewer find it first. This belongs in the trade-offs/out-of-scope area, not buried.
- **Trade-offs** (compact, spec §7 declarative definition, §17 outbox-vs-broker, §4 Core-Banking-as-stub, spec §1 seams-not-machinery) — 3-4 one-line bullets, "chose X over Y because Z" format.
- **Extensibility (not built)** — 3-4 sentences on the three seams (spec §7-9), explicitly labeled not-built.
- **Assumptions / out of scope** (spec §21) — compact two-column list.

- [ ] **Step 2: Write `docs/lld.md`**

Structure:
- **Approval state machine diagram** (spec §6) with the one-line note "`APPROVED` means the approval requirement is satisfied — not that money has moved."
- **Transfer release lifecycle diagram** (spec §6, the `TransferState` enum from Task 10).
- **Workflow definition** — the actual YAML from Task 1 Step 2 (or the fallback table if the tripwire was hit), plus the one-sentence "loaded at startup, small fixed guard registry, no expression language" note.
- **Policy contract** — one line, not a diagram (fix #2 trade): `PolicyResolver.resolve(amountMinorUnits) -> ApprovalPolicy{requiredApprovals, eligibleRoles, makerCanApprove}`, frozen into `policy_snapshot` on the `ApprovalRequest` row.
- **API contracts** — full JSON per fix #2: `POST /approvals` request/response (Task 9's `CreateApprovalRequestDto`/`ApprovalResponseDto`), `POST /approvals/{id}/approve` request/response including both `409` bodies (Task 9's `ErrorResponseDto` for `CONCURRENT_STATE_CHANGE` and `INVALID_STATE_TRANSITION`), `POST /transfers` request/response (Task 15's `SubmitTransferDto`/`TransferResponseDto`).
- **Data model** — the table/column list from spec §18, cross-checked against the actual `@Column` names in Task 3 and Task 10's entities so the doc matches the code exactly.
- **Three sequence diagrams**:
  - Auto-release: `Transfer→Engine create` / engine emits `ApprovalSubmitted`+`ApprovalApproved` (Task 4) / relay delivers (Task 7) / Transfer releases (Task 14).
  - Multi-approver **as the race diagram** per fix #3: Checker A and Checker B concurrent, one guarded-UPDATE winner, one `409 CONCURRENT_STATE_CHANGE` loser (Task 5/Task 6's test is the source of truth for this sequence).
  - Expiry: sweeper's per-row guarded transition (Task 8), including the expiry-vs-approve race outcome from Task 8 Step 5.
- **Concurrency/race handling** — the guarded-UPDATE pseudocode from spec §12 plus the rollback invariant from spec §13, and a one-line pointer to the actual tests (Task 6, Task 8 Step 5) as evidence.
- **Failure semantics table** (spec §15/§21).

- [ ] **Step 3: Verify the page budget**

Render both files to PDF (or open as Markdown preview) and confirm the combined length is ≤4 pages at a normal font size. If over, cut from the "Extensibility (not built)" section and the trade-offs bullets first — those are the sections the assignment doesn't explicitly require; never cut the state machine, API contracts, sequence diagrams, or race handling to make room.

- [ ] **Step 4: Commit**

```bash
git add docs/hld.md docs/lld.md
git commit -m "docs: submission HLD/LLD within the 4-page budget"
```

---

## Plan Self-Review

**Spec coverage:** §1-4 (thesis, stack, repo, ownership) → Tasks 1, 3, 10. §5 (comm/failure) → Tasks 7, 12, 13, 14, 16. §6 (state machines) → Tasks 1, 3, 5, 10, 13. §7 (YAML seam + tripwire + startup guard validation) → Tasks 1, 2, 4. §8 (policy snapshot) → Tasks 2, 3, 10, 12. §9 (envelope) → Task 3. §10 (maker guard) → Task 5. §11 (idempotency, incl. why approve/reject/cancel don't take a client key) → Tasks 4, 5, 9, 11, 13, 14. §12-13 (OCC, N-of-M, rollback) → Tasks 3, 5, 6. §14 (audit) → Task 3-5, 8. §15 (expiry) → Task 8. §16-17 (events, outbox, claim-based relay) → Tasks 3, 4, 7. §18 (data model) → Tasks 3, 10. §19 (API contracts) → Tasks 9, 15. §20 (testing priority) → reflected in task ordering (state/guards before controllers throughout). §21 (out of scope) → Task 17 README, Task 18 docs. §22 (build order) → this plan's task ordering. No gaps found.

**Placeholder scan:** no TBD/TODO markers; every step above has real code or, for Task 18 (documentation), an explicit content structure with spec section references rather than a bare "write the docs" instruction.

**Type consistency:** `ApprovalRequestView`, `CreateApprovalRequest`, `ConcurrentStateChangeException`, `InvalidStateTransitionException` (Tasks 4-5) are reused with identical signatures through Tasks 6, 8, 9. `TransferView`, `SubmitTransferCommand` (Task 13) match Task 15's controller usage. `IncomingEvent` (Task 14) matches Task 15's `EventWebhookController` construction from `X-Event-Id`/`X-Event-Type` headers + `IncomingEventDto.requestId()` body — verified consistent. `WorkflowResponse.state()` (Task 12) is read as a raw string by `TransferSubmissionService` (Task 13) rather than deserialized into `ApprovalState` — deliberate, since Transfer Service never needs to interpret the engine's state enum, only to store `approvalRequestId` and wait for the async event.

**Post-review fixes (four issues raised against this plan before execution, all applied above):**
1. *Outbox relay claim race* — `OutboxEventRepository` (Task 3) gained `selectAndLockUnpublishedIds`/`markClaimed`; `OutboxRelay` (Task 7) now claims a batch via `FOR UPDATE SKIP LOCKED` in a short transaction before publishing outside it, with a 30s stale-claim reclaim for crash recovery. Safe for >1 relay instance even though this exercise runs one.
2. *Release idempotency contract* — `CoreBankingClient.release` (Task 11) now documents its idempotent-by-`transferId` contract explicitly; the stub already implemented it, this made the guarantee visible rather than incidental.
3. *Unused `idempotencyKey` on approve/reject/cancel* — removed from `ApprovalCommandService` (Task 5), its tests (Tasks 5, 6, 8), and `ApprovalController` (Task 9); decision-level idempotency now rests entirely on Task 3's `UNIQUE(request_id, actor_id)` constraint, documented in spec §11.
4. *Weak YAML validation* — `YamlWorkflowLoader.validate()` (Task 1) now checks `initialState` is declared and transition names are unique, plus a negative-path test proving a malformed definition fails fast; guard-name validation against the live `GuardRegistry` moved to `WorkflowConfig` (Task 4, new `WorkflowConfigTest`) since that's the first point both beans exist.

**Round 2 post-review fixes (four more issues, all verified against the actual code before fixing, not taken on faith):**
1. *Quorum undercounting race* — CONFIRMED by tracing the transaction interleaving: with `required=2`, two concurrent `approve()` calls under READ COMMITTED can each count only their own decision (the other's insert isn't committed yet), both see quorum unmet, and the request gets stuck in `PENDING_APPROVAL` with two decisions and no transition — Task 6 previously only tested `required=1`, where a single decision always self-satisfies quorum, so this never surfaced. Fixed with a `SELECT ... FOR UPDATE` row lock (`ApprovalRequestRepository.findByRequestIdForUpdate`, Task 3) taken at the top of `approve`/`reject`/`cancel` (Task 5's `loadOrThrow`), serializing quorum counting on the same request. This is a deliberate, documented exception to the "no explicit row locks" global constraint — the guarded UPDATE alone only ever protected the transition *attempt*, not the *decision* to attempt one. New regression test in Task 6.
2. *Self-invocation silently disabling `@Transactional`* — CONFIRMED as a real defect, and ironic: `OutboxRelay.relayOnce()` called `this.claimBatch()`/`this.markPublished()`, and `ExpirySweeper.sweepOnce()` called `this.expireOne()`; Spring's proxy-based AOP never intercepts self-invocation, so those `@Transactional` annotations were silent no-ops. Concretely, this meant the `FOR UPDATE SKIP LOCKED` claim lock from Round 1's outbox fix was released the instant the SELECT returned — before `markClaimed` ran — fully defeating that fix. Resolved by extracting `OutboxClaimService` (Task 7) and `ExpiryTransitionService` (Task 8) as separate beans, called through their real proxies.
3. *No funds hold* — CONFIRMED as a real banking-correctness gap (double-spend across concurrent pending transfers). Documented explicitly rather than built, given the time budget — see spec §5/§21 and the README's "what I'd do differently."
4. *`submit()`'s open transaction spanning the engine HTTP call* — CONFIRMED, and worse on inspection than first flagged: a crash between the engine call succeeding and the local commit both stranded the transfer *and* permanently corrupted the stub's in-memory duplicate-key set, blocking all future retries of that idempotency key — and because `transferId` was freshly minted inside `submit()` on every call, an unprotected retry wouldn't even hit the engine's idempotency check, it would silently create a second workflow. Fixed by splitting into `TransferPersistenceService` (Task 13, a separate bean for the same self-invocation reason as #2) with two commit points around the engine call, and a resume path that reuses the *persisted* `transferId` and `expiresAt` on retry rather than recomputing either — recomputing `expiresAt` specifically would have changed the engine's idempotency hash and turned a legitimate retry into a spurious `409 IDEMPOTENCY_CONFLICT`, a bug in the fix that was caught before being written. `Transfer` gained a persisted `expiresAt` column (Task 10) to support this; Task 14's test helpers updated to set it (now `NOT NULL`).

Also applied: `?stringtype=unspecified` on the approval-engine JDBC URL (Task 1, Task 16) — several columns are `jsonb` fed by a plain Java `String` (via converter or direct field), which the Postgres JDBC driver would otherwise bind as `varchar` and Postgres would reject.

