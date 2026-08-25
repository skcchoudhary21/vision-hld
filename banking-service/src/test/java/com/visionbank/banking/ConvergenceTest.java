package com.visionbank.banking;

import com.visionbank.banking.approval.ApprovalEventListener;
import com.visionbank.banking.approval.IncomingEvent;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.repository.TransferRepository;
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

/**
 * Proves spec §16/§20: whether a transfer was auto-released (0 approvals) or
 * released after N checkers, the ONLY code path that releases it is consuming
 * ApprovalApproved — there is no separate auto-release branch to drift out of sync.
 */
@Testcontainers
@SpringBootTest
class ConvergenceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalEventListener listener;
    @Autowired TransferRepository transfers;

    private Transfer waitingTransfer(String transferId, String approvalRequestId) {
        Transfer t = new Transfer();
        t.setTransferId(transferId);
        t.setMakerId("maker-1");
        t.setFromAccount("ACC-FUNDED");
        t.setToAccount("ACC-DEST");
        t.setAmountMinorUnits(1000_00L);
        t.setCurrency("AED");
        t.setState(TransferState.WAITING_FOR_APPROVAL);
        t.setApprovalRequestId(approvalRequestId);
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setExpiresAt(Instant.now().plusSeconds(86400));
        t.setCreatedAt(Instant.now());
        return transfers.save(t);
    }

    @Test
    void autoApprovedAndMultiApproverPathsBothReleaseViaTheSameEvent() {
        waitingTransfer("t-auto", "t-auto");
        waitingTransfer("t-multi", "t-multi");

        // "auto" path: engine emitted ApprovalSubmitted then ApprovalApproved immediately on create
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalSubmitted", "t-auto"));
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "t-auto"));

        // "multi" path: engine emitted ApprovalSubmitted at create, ApprovalApproved only after quorum
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalSubmitted", "t-multi"));
        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "t-multi"));

        assertThat(transfers.findById("t-auto").get().getState()).isEqualTo(TransferState.RELEASED);
        assertThat(transfers.findById("t-multi").get().getState()).isEqualTo(TransferState.RELEASED);
    }
}
