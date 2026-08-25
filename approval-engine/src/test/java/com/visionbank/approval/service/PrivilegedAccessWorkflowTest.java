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
