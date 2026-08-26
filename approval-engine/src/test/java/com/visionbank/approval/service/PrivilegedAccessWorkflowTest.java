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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // "sec-1" retrying with the SECURITY role after the row already moved on to
        // MANAGER_APPROVAL is ineligible there -- this exercises the fallback (an actor
        // who has decided ANYWHERE on the request gets a harmless no-op instead of a hard
        // Forbidden), not the state-scoped idempotency check itself (that's the next test).
        service.approve(created.requestId(), "sec-1", "SECURITY");
        ApprovalRequestView replay = service.approve(created.requestId(), "sec-1", "SECURITY");
        assertThat(replay.state()).isEqualTo("MANAGER_APPROVAL"); // already moved on

        // the SAME actor id acting again at a later stage (different role) is a genuinely
        // new decision -- proves the widened (request_id, actor_id, state) constraint works.
        ApprovalRequestView afterManager = service.approve(created.requestId(), "sec-1", "MANAGER");
        assertThat(afterManager.state()).isEqualTo("COMPLIANCE_REVIEW");
    }

    @Test
    void sameActorApprovingTheSameStageTwiceWhileItsStillCurrentIsAGenuineIdempotentReplay() {
        Map<String, StagePolicy> stages = Map.of(
                "SECURITY_REVIEW", new StagePolicy(2, List.of("SECURITY")),
                "MANAGER_APPROVAL", new StagePolicy(1, List.of("MANAGER")),
                "COMPLIANCE_REVIEW", new StagePolicy(1, List.of("COMPLIANCE")));
        CreateApprovalRequest cmd = new CreateApprovalRequest("priv-idem-1", "PRIVILEGED_ACCESS", "maker-1",
                new PolicySnapshot("v1", stages, false), "{}", Instant.now().plusSeconds(86400));
        String id = service.create(cmd, UUID.randomUUID().toString()).requestId();

        ApprovalRequestView first = service.approve(id, "sec-1", "SECURITY");
        assertThat(first.state()).isEqualTo("SECURITY_REVIEW"); // quorum not yet met (1 of 2)

        ApprovalRequestView replay = service.approve(id, "sec-1", "SECURITY");
        assertThat(replay.state()).isEqualTo("SECURITY_REVIEW"); // still not met -- the SAME decision replayed, not counted twice
    }

    @Test
    void createRejectsAnIncompleteStagePolicyMapInsteadOfWedgingLater() {
        // Only SECURITY_REVIEW is covered -- MANAGER_APPROVAL and COMPLIANCE_REVIEW are
        // missing. Without this validation, create() would succeed and the request would
        // wedge with an uncaught IllegalStateException the moment it reached MANAGER_APPROVAL.
        Map<String, StagePolicy> incompleteStages = Map.of(
                "SECURITY_REVIEW", new StagePolicy(1, List.of("SECURITY")));
        CreateApprovalRequest cmd = new CreateApprovalRequest("priv-incomplete-1", "PRIVILEGED_ACCESS", "maker-1",
                new PolicySnapshot("v1", incompleteStages, false), "{}", Instant.now().plusSeconds(86400));

        assertThatThrownBy(() -> service.create(cmd, UUID.randomUUID().toString()))
                .isInstanceOf(InvalidRequestException.class);
    }
}
