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
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
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
