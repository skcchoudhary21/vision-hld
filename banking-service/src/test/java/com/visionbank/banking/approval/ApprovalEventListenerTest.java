package com.visionbank.banking.approval;

import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.repository.ProcessedEventRepository;
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
        t.setState(TransferState.PENDING_APPROVAL);
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

    private Transfer failedTransfer(String transferId) {
        Transfer t = new Transfer();
        t.setTransferId(transferId);
        t.setMakerId("maker-1");
        t.setFromAccount("ACC-FUNDED");
        t.setToAccount("ACC-DEST");
        t.setAmountMinorUnits(1000_00L);
        t.setCurrency("AED");
        t.setState(TransferState.FAILED);
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
    void eventArrivingWhileStillCreatedLinksTheTransferThenProcessesIt() {
        // The bug this class now guards against: before the fix, an event arriving
        // while the transfer was still CREATED threw TransferNotYetVisibleException
        // forever, because nothing in production ever called markPendingApproval.
        // The corrected behavior is that the first event to arrive performs the link
        // itself (see ApprovalEventListener.handle()) and then falls through to the
        // same switch every other event uses -- no throw, no permanently stuck transfer.
        createdTransfer("t-created-1");
        String eventId = UUID.randomUUID().toString();

        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "t-created-1"));

        Transfer updated = transfers.findById("t-created-1").get();
        assertThat(updated.getApprovalRequestId()).isEqualTo("t-created-1");
        assertThat(updated.getState()).isEqualTo(TransferState.RELEASED);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    @Test
    void eventArrivingWhileFailedResumesAndReleasesTheTransfer() {
        // Regression test for the FAILED-resume bug: a transfer whose earlier creation
        // attempt gave up (ApprovalCreationFailed -> FAILED) and was then resumed with the
        // same Idempotency-Key gets a brand-new live workflow on approval-engine's side.
        // Before this fix, handle() only linked from CREATED, so every event for the
        // resumed workflow fell through to markProcessed and was silently discarded --
        // the transfer stayed FAILED forever while the engine's workflow kept progressing.
        // The corrected behavior links from FAILED exactly as it does from CREATED.
        failedTransfer("t-failed-resumed-1");
        String eventId = UUID.randomUUID().toString();

        listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "t-failed-resumed-1"));

        Transfer updated = transfers.findById("t-failed-resumed-1").get();
        assertThat(updated.getApprovalRequestId()).isEqualTo("t-failed-resumed-1");
        assertThat(updated.getState()).isEqualTo(TransferState.RELEASED);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    @Test
    void eventArrivingForATransferThatDoesNotExistAtAllStillThrows() {
        // Distinct from the CREATED case above: if the row itself isn't visible yet
        // (e.g. persistCreated hasn't committed), findById returns empty and the
        // listener still has nothing to link -- this is the one case that legitimately
        // needs the retry-via-exception path.
        String eventId = UUID.randomUUID().toString();

        assertThrows(TransferNotYetVisibleException.class, () ->
                listener.handle(new IncomingEvent(eventId, "ApprovalApproved", "t-does-not-exist")));

        assertThat(processedEvents.existsById(eventId)).isFalse();
    }

    @Test
    void retryAfterLinkingSucceeds() {
        Transfer t = createdTransfer("t-created-2");
        t.setState(TransferState.PENDING_APPROVAL);
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
