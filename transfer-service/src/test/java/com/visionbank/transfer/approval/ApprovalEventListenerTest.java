package com.visionbank.transfer.approval;

import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.ProcessedEventRepository;
import com.visionbank.transfer.repository.TransferRepository;
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
class ApprovalEventListenerTest {

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
    @Autowired ProcessedEventRepository processedEvents;

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
    void approvalApprovedEventReleasesTheTransfer() {
        waitingTransfer("t-1", "req-1");

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "req-1"));

        assertThat(transfers.findById("t-1").get().getState()).isEqualTo(TransferState.RELEASED);
    }

    @Test
    void approvalRejectedEventMarksTransferRejected() {
        waitingTransfer("t-2", "req-2");

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalRejected", "req-2"));

        assertThat(transfers.findById("t-2").get().getState()).isEqualTo(TransferState.REJECTED);
    }

    @Test
    void duplicateEventDeliveryIsANoOp() {
        waitingTransfer("t-3", "req-3");
        String eventId = UUID.randomUUID().toString();

        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "req-3"));
        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "req-3")); // redelivery, same eventId

        assertThat(transfers.findById("t-3").get().getState()).isEqualTo(TransferState.RELEASED);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }
}
