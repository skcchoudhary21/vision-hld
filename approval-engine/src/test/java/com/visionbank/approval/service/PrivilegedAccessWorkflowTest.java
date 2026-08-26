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

        ApprovalRequestView afterSecurity = service.approve(created.requestId(), "sec-1", "SECURITY_CHECKER");
        assertThat(afterSecurity.state()).isEqualTo("MANAGER_APPROVAL");

        ApprovalRequestView afterManager = service.approve(created.requestId(), "mgr-1", "MANAGER_CHECKER");
        assertThat(afterManager.state()).isEqualTo("COMPLIANCE_REVIEW");

        ApprovalRequestView afterCompliance = service.approve(created.requestId(), "comp-1", "COMPLIANCE_CHECKER");
        assertThat(afterCompliance.state()).isEqualTo("APPROVED");
    }

    @Test
    void rejectionAtAnyStageEndsTheWorkflow() {
        ApprovalRequestView created = service.create(cmd("priv-2"), UUID.randomUUID().toString());
        service.approve(created.requestId(), "sec-1", "SECURITY_CHECKER");

        ApprovalRequestView rejected = service.reject(created.requestId(), "mgr-1", "MANAGER_CHECKER");

        assertThat(rejected.state()).isEqualTo("REJECTED");
    }

    @Test
    void sameActorCannotDoubleCountWithinOneStageButCanActAtALaterStage() {
        ApprovalRequestView created = service.create(cmd("priv-3"), UUID.randomUUID().toString());

        // "sec-1" retrying with the SECURITY_CHECKER role after the row already moved on to
        // MANAGER_APPROVAL is ineligible there -- this exercises the fallback (an actor
        // who has decided ANYWHERE on the request gets a harmless no-op instead of a hard
        // Forbidden), not the state-scoped idempotency check itself (that's the next test).
        service.approve(created.requestId(), "sec-1", "SECURITY_CHECKER");
        ApprovalRequestView replay = service.approve(created.requestId(), "sec-1", "SECURITY_CHECKER");
        assertThat(replay.state()).isEqualTo("MANAGER_APPROVAL"); // already moved on

        // the SAME actor id acting again at a later stage (different role) is a genuinely
        // new decision -- proves the (request_id, actor_id, state) constraint works.
        ApprovalRequestView afterManager = service.approve(created.requestId(), "sec-1", "MANAGER_CHECKER");
        assertThat(afterManager.state()).isEqualTo("COMPLIANCE_REVIEW");
    }

    @Test
    void sameActorApprovingTheSameStageTwiceWhileItsStillCurrentIsAGenuineIdempotentReplay() {
        // privileged-access:v2 requires 2 SECURITY_CHECKER approvals (v1 requires only 1) --
        // exercises both the idempotent-replay-under-unmet-quorum behavior AND that the
        // versioned registry genuinely resolves a DIFFERENT definition for the same
        // workflowId, per the design's registry-versioning requirement.
        String id = service.create(cmdV2("priv-idem-1"), UUID.randomUUID().toString()).requestId();

        ApprovalRequestView first = service.approve(id, "sec-1", "SECURITY_CHECKER");
        assertThat(first.state()).isEqualTo("SECURITY_REVIEW"); // quorum not yet met (1 of 2)

        ApprovalRequestView replay = service.approve(id, "sec-1", "SECURITY_CHECKER");
        assertThat(replay.state()).isEqualTo("SECURITY_REVIEW"); // still not met -- the SAME decision replayed, not counted twice
    }
}
