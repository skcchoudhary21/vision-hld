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
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
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
        // Reuse the exact same command instance: a real replay resends byte-identical
        // request bytes, which is what the hash-based conflict check keys off of.
        // Calling cmd() twice would re-evaluate Instant.now() for expiresAt, producing
        // a different hash and (correctly) tripping the conflict path exercised below.
        CreateApprovalRequest body = cmd("idem-1", 0);
        ApprovalRequestView first = service.create(body, key);

        ApprovalRequestView second = service.create(body, key);

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

    @Test
    void reusingRequestIdUnderADifferentKeyThrowsConflictRatherThanOverwriting() {
        service.create(cmd("reuse-1", 2), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.create(cmd("reuse-1", 0), UUID.randomUUID().toString()))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
