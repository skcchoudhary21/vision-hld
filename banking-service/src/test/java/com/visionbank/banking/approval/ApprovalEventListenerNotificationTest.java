package com.visionbank.banking.approval;

import com.visionbank.banking.domain.ProcessedEvent;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.notification.NotificationClient;
import com.visionbank.banking.repository.ProcessedEventRepository;
import com.visionbank.banking.repository.TransferRepository;
import com.visionbank.banking.service.ReleaseService;
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

    private ApprovalEventListener listener() {
        return new ApprovalEventListener(transfers, processedEvents, releaseService, notifications);
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
