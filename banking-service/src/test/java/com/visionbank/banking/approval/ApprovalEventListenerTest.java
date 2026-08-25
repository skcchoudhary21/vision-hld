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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private Transfer createdTransfer(String transferId) {
        Transfer t = new Transfer();
        t.setTransferId(transferId);
        t.setMakerId("maker-1");
        t.setFromAccount("ACC-FUNDED");
        t.setToAccount("ACC-DEST");
        t.setAmountMinorUnits(1000_00L);
        t.setCurrency("AED");
        t.setState(TransferState.CREATED);
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setExpiresAt(Instant.now().plusSeconds(86400));
        t.setCreatedAt(Instant.now());
        return transfers.save(t);
    }

    @Test
    void approvalApprovedEventReleasesTheTransfer() {
        waitingTransfer("t-1", "t-1");

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "t-1"));

        assertThat(transfers.findById("t-1").get().getState()).isEqualTo(TransferState.RELEASED);
    }

    @Test
    void approvalRejectedEventMarksTransferRejected() {
        waitingTransfer("t-2", "t-2");

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalRejected", "t-2"));

        assertThat(transfers.findById("t-2").get().getState()).isEqualTo(TransferState.REJECTED);
    }

    @Test
    void duplicateEventDeliveryIsANoOp() {
        waitingTransfer("t-3", "t-3");
        String eventId = UUID.randomUUID().toString();

        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "t-3"));
        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "t-3")); // redelivery, same eventId

        assertThat(transfers.findById("t-3").get().getState()).isEqualTo(TransferState.RELEASED);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    @Test
    void eventArrivingBeforeTransferIsLinkedThrowsAndDoesNotMarkProcessed() {
        createdTransfer("t-created-1");
        String eventId = UUID.randomUUID().toString();

        assertThrows(TransferNotYetVisibleException.class, () ->
                listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "t-created-1")));

        assertThat(processedEvents.existsById(eventId)).isFalse();
        assertThat(transfers.findById("t-created-1").get().getState()).isEqualTo(TransferState.CREATED);
    }

    @Test
    void retryAfterLinkingSucceeds() {
        Transfer t = createdTransfer("t-created-2");
        t.setState(TransferState.WAITING_FOR_APPROVAL);
        t.setApprovalRequestId("t-created-2");
        transfers.save(t);
        String eventId = UUID.randomUUID().toString();

        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "t-created-2"));

        assertThat(transfers.findById("t-created-2").get().getState()).isEqualTo(TransferState.RELEASED);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    @Test
    void staleEventAfterSettlementIsAPermanentNoOp() {
        Transfer t = createdTransfer("t-settled-1");
        t.setState(TransferState.RELEASED);
        t.setApprovalRequestId("t-settled-1");
        transfers.save(t);
        String eventId = UUID.randomUUID().toString();

        listener.handle(new IncomingEvent(eventId, "ApprovalRejected", "t-settled-1"));

        assertThat(transfers.findById("t-settled-1").get().getState()).isEqualTo(TransferState.RELEASED);
        assertThat(processedEvents.existsById(eventId)).isTrue(); // marked processed — no perpetual retry
    }
}
