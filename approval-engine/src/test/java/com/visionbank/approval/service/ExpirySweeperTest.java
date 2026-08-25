package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
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
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ExpirySweeper sweeper;
    @Autowired ApprovalRequestRepository requests;

    private ApprovalRequest stalePendingRequest(String id) {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId(id);
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState("PENDING_APPROVAL");
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
