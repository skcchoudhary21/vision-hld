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
import java.util.concurrent.*;

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

    @Test
    void concurrentCreateWithSameIdempotencyKeyNeverThrowsRawConstraintViolation() throws Exception {
        String idemKey = UUID.randomUUID().toString();
        CreateApprovalRequest cmd = new CreateApprovalRequest("req-race", "TRANSFER_APPROVAL", "maker-1",
                "transfer-single-checker", 1, "v1", "{}", Instant.now().plusSeconds(86400));

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Object> attempt = () -> {
            startGate.await();
            try {
                return service.create(cmd, idemKey);
            } catch (Exception e) {
                return e;
            }
        };
        Future<Object> a = pool.submit(attempt);
        Future<Object> b = pool.submit(attempt);
        startGate.countDown();

        Object resultA = a.get(10, TimeUnit.SECONDS);
        Object resultB = b.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(resultA).isInstanceOf(ApprovalRequestView.class);
        assertThat(resultB).isInstanceOf(ApprovalRequestView.class);
        assertThat(((ApprovalRequestView) resultA).requestId()).isEqualTo(((ApprovalRequestView) resultB).requestId());
    }
}
