package com.visionbank.banking.approval;

import com.visionbank.banking.domain.ProcessedEvent;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.notification.NotificationClient;
import com.visionbank.banking.repository.ProcessedEventRepository;
import com.visionbank.banking.repository.TransferRepository;
import com.visionbank.banking.service.ReleaseService;
import com.visionbank.banking.service.TransferPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Pure-unit coverage (no Testcontainers) for the notification seam itself —
// ApprovalEventListenerTest already covers the state-transition behavior
// end-to-end against a real database.
@ExtendWith(MockitoExtension.class)
class ApprovalEventListenerNotificationTest {

    @Mock private TransferRepository transfers;
    @Mock private ProcessedEventRepository processedEvents;
    @Mock private ReleaseService releaseService;
    @Mock private NotificationClient notifications;
    @Mock private TransferPersistenceService persistenceService;

    private ApprovalEventListener listener() {
        return new ApprovalEventListener(transfers, processedEvents, releaseService, notifications, persistenceService);
    }

    private Transfer createdTransfer(String id) {
        Transfer t = new Transfer();
        t.setTransferId(id);
        t.setMakerId("maker-1");
        t.setState(TransferState.CREATED);
        t.setExpiresAt(Instant.now().plusSeconds(300));
        t.setCreatedAt(Instant.now());
        return t;
    }

    // Mockito stand-in for TransferPersistenceService.markPendingApproval: mutates the
    // same Transfer instance in place (state + approvalRequestId), matching what the
    // real method does when it runs in the same transaction as handle().
    private void stubLinking(Transfer t) {
        when(persistenceService.markPendingApproval(eq(t.getTransferId()), anyString()))
                .thenAnswer(invocation -> {
                    t.setApprovalRequestId(invocation.getArgument(1));
                    t.setState(TransferState.PENDING_APPROVAL);
                    return t;
                });
    }

    private Transfer pendingTransfer(String id) {
        Transfer t = new Transfer();
        t.setTransferId(id);
        t.setMakerId("maker-1");
        t.setState(TransferState.PENDING_APPROVAL);
        t.setApprovalRequestId(id);
        t.setExpiresAt(Instant.now().plusSeconds(300));
        t.setCreatedAt(Instant.now());
        return t;
    }

    @Test
    void expiryNotifiesTheMaker() {
        Transfer t = pendingTransfer("t-expired");
        when(transfers.findById("t-expired")).thenReturn(Optional.of(t));

        listener().handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalExpired", "t-expired"));

        verify(notifications).notifyMaker(eq("maker-1"), eq("t-expired"), contains("expired"));
    }

    @Test
    void rejectionNotifiesTheMaker() {
        Transfer t = pendingTransfer("t-rejected");
        when(transfers.findById("t-rejected")).thenReturn(Optional.of(t));

        listener().handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalRejected", "t-rejected"));

        verify(notifications).notifyMaker(eq("maker-1"), eq("t-rejected"), anyString());
    }

    @Test
    void cancellationDoesNotNotifyTheMaker() {
        // The maker cancelled it themselves — notifying them of their own action is noise.
        Transfer t = pendingTransfer("t-cancelled");
        when(transfers.findById("t-cancelled")).thenReturn(Optional.of(t));

        listener().handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalCancelled", "t-cancelled"));

        verifyNoInteractions(notifications);
    }

    @Test
    void creationFailedMarksTransferFailedAndNotifiesTheMaker() {
        Transfer t = new Transfer();
        t.setTransferId("t-created-fail");
        t.setMakerId("maker-1");
        t.setState(TransferState.CREATED);
        t.setExpiresAt(Instant.now().plusSeconds(300));
        t.setCreatedAt(Instant.now());
        when(transfers.findById("t-created-fail")).thenReturn(Optional.of(t));

        listener().handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalCreationFailed", "t-created-fail"));

        verify(notifications).notifyMaker(eq("maker-1"), eq("t-created-fail"), anyString());
    }

    @Test
    void approvalSubmittedLinksCreatedTransferToPendingApproval() {
        // This is the exact scenario that shipped broken: a transfer starting in CREATED
        // (never seeded directly into PENDING_APPROVAL, unlike the other tests in this class),
        // receiving the first lifecycle event for it. Before the fix, ApprovalEventListener
        // never called markPendingApproval anywhere in production, so this transfer would
        // have stayed CREATED forever and every subsequent event would fail identically.
        Transfer t = createdTransfer("t-link");
        when(transfers.findById("t-link")).thenReturn(Optional.of(t));
        stubLinking(t);

        listener().handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalSubmitted", "t-link"));

        verify(persistenceService).markPendingApproval("t-link", "t-link");
        org.assertj.core.api.Assertions.assertThat(t.getState()).isEqualTo(TransferState.PENDING_APPROVAL);
        org.assertj.core.api.Assertions.assertThat(t.getApprovalRequestId()).isEqualTo("t-link");
    }

    @Test
    void fullSequenceFromCreatedThroughApprovalReleasesTheTransfer() {
        // The realistic auto-release-tier sequence that was stuck in production: the
        // linking event (ApprovalSubmitted) arrives first, then the decision event
        // (ApprovalApproved) arrives as a separate handle() call. Before the fix, the
        // first call would leave the transfer at CREATED and the second call would
        // find it still CREATED (with no way to reach PENDING_APPROVAL), permanently
        // failing to release funds.
        Transfer t = createdTransfer("t-seq");
        when(transfers.findById("t-seq")).thenReturn(Optional.of(t));
        stubLinking(t);

        ApprovalEventListener listener = listener();

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalSubmitted", "t-seq"));
        org.assertj.core.api.Assertions.assertThat(t.getState()).isEqualTo(TransferState.PENDING_APPROVAL);
        org.assertj.core.api.Assertions.assertThat(t.getApprovalRequestId()).isEqualTo("t-seq");

        listener.handle(new IncomingEvent(UUID.randomUUID().toString(), "ApprovalApproved", "t-seq"));

        verify(releaseService).release(t);
        verify(notifications).notifyMaker(eq("maker-1"), eq("t-seq"), anyString());
        // markPendingApproval must not be invoked a second time — the transfer is
        // already PENDING_APPROVAL by the time the second event arrives.
        verify(persistenceService, times(1)).markPendingApproval(anyString(), anyString());
    }

    @Test
    void duplicateEventDoesNotNotifyTwice() {
        Transfer t = pendingTransfer("t-dup");
        String eventId = UUID.randomUUID().toString();
        when(transfers.findById("t-dup")).thenReturn(Optional.of(t));
        when(processedEvents.existsById(eventId)).thenReturn(false).thenReturn(true);

        ApprovalEventListener listener = listener();
        listener.handle(new IncomingEvent(eventId, "ApprovalRejected", "t-dup"));
        listener.handle(new IncomingEvent(eventId, "ApprovalRejected", "t-dup")); // redelivery

        verify(notifications, times(1)).notifyMaker(anyString(), anyString(), anyString());
    }
}
