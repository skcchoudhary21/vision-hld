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
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
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
