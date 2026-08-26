package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalDecision;
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
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalCommandService service;
    @Autowired ApprovalRequestRepository requests;
    @Autowired ApprovalDecisionRepository decisions;

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
        assertThat(requests.findByRequestId(id).get().getState()).isEqualTo("APPROVED");
        assertThat(decisions.countByRequestIdAndDecisionAndState(id, ApprovalDecision.DecisionType.APPROVE, "PENDING_APPROVAL")).isEqualTo(2);
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
        assertThat(requests.findByRequestId(id).get().getState()).isEqualTo("APPROVED");
        assertThat(decisions.countByRequestIdAndDecisionAndState(id, ApprovalDecision.DecisionType.APPROVE, "PENDING_APPROVAL")).isEqualTo(1);
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
        String finalState = requests.findByRequestId(id).get().getState();
        assertThat(finalState).isIn("CANCELLED", "APPROVED");
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
