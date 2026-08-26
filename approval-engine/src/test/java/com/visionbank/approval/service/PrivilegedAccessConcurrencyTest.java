package com.visionbank.approval.service;

import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.domain.StagePolicy;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PrivilegedAccessConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalCommandService service;

    private String create(String requestId) {
        Map<String, StagePolicy> stages = Map.of(
                "SECURITY_REVIEW", new StagePolicy(1, List.of("SECURITY")),
                "MANAGER_APPROVAL", new StagePolicy(1, List.of("MANAGER")),
                "COMPLIANCE_REVIEW", new StagePolicy(1, List.of("COMPLIANCE")));
        CreateApprovalRequest cmd = new CreateApprovalRequest(requestId, "PRIVILEGED_ACCESS", "maker-1",
                new PolicySnapshot("v1", stages, false), "{}", Instant.now().plusSeconds(86400));
        return service.create(cmd, UUID.randomUUID().toString()).requestId();
    }

    @Test
    void twoActorsApprovingTheSameStageConcurrently_exactlyOneWins() throws Exception {
        String id = create("race-2");
        service.approve(id, "sec-1", "SECURITY"); // -> MANAGER_APPROVAL
        service.approve(id, "mgr-1", "MANAGER");   // -> COMPLIANCE_REVIEW

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        var f1 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return service.approve(id, "comp-1", "COMPLIANCE");
        });
        var f2 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return service.approve(id, "comp-2", "COMPLIANCE");
        });
        ready.await();
        go.countDown();

        // required=1 for COMPLIANCE_REVIEW, the last stage before the terminal APPROVED:
        // whichever commits first satisfies quorum and reaches APPROVED; the loser's own
        // initial read (after acquiring the row lock, which the winner already released)
        // already sees the post-commit APPROVED state, finds no "approve" transition from
        // a terminal state, and classifyRaceOrIllegal correctly resolves it as a race
        // rather than illegal.
        int successes = 0;
        Exception caught = null;
        for (var f : List.of(f1, f2)) {
            try {
                f.get();
                successes++;
            } catch (Exception e) {
                caught = e;
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(caught).isNotNull();
        assertThat(caught.getCause()).isInstanceOf(ConcurrentStateChangeException.class);
        pool.shutdown();
    }

    @Test
    void rejectingAStageAlreadyApprovedPastIsClassifiedAsARaceNotIllegal() {
        String id = create("race-3");
        service.approve(id, "sec-1", "SECURITY");   // -> MANAGER_APPROVAL
        service.approve(id, "mgr-1", "MANAGER");     // -> COMPLIANCE_REVIEW
        service.approve(id, "comp-1", "COMPLIANCE"); // -> APPROVED, terminal, no "reject" from here

        // A stale actor attempting "reject" after the row reached a terminal state with no
        // reject transition from it must get CONCURRENT_STATE_CHANGE (this action WAS legal
        // somewhere along the way, just lost the race entirely), not INVALID_STATE_TRANSITION.
        assertThatThrownBy(() -> service.reject(id, "sec-stale", "SECURITY"))
                .isInstanceOf(ConcurrentStateChangeException.class);
    }

    @Test
    void cancellingAWorkflowThatNeverOffersCancelIsIllegalRegardlessOfState() {
        String id = create("race-4");

        // privileged-access.yaml defines zero "cancel" transitions anywhere -- candidateStarts
        // for that action name is empty, so classifyRaceOrIllegal must return
        // INVALID_STATE_TRANSITION unconditionally, regardless of the row's actual state/version.
        assertThatThrownBy(() -> service.cancel(id, "maker-1"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
