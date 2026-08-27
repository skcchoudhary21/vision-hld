package com.visionbank.banking.approval;

import com.visionbank.banking.domain.ProcessedEvent;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.notification.NotificationClient;
import com.visionbank.banking.repository.ProcessedEventRepository;
import com.visionbank.banking.repository.TransferRepository;
import com.visionbank.banking.service.ReleaseService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class ApprovalEventListener {

    private final TransferRepository transfers;
    private final ProcessedEventRepository processedEvents;
    private final ReleaseService releaseService;
    private final NotificationClient notifications;

    public ApprovalEventListener(TransferRepository transfers, ProcessedEventRepository processedEvents,
                                  ReleaseService releaseService, NotificationClient notifications) {
        this.transfers = transfers;
        this.processedEvents = processedEvents;
        this.releaseService = releaseService;
        this.notifications = notifications;
    }

    @Transactional
    public void handle(IncomingEvent event) {
        if (processedEvents.existsById(event.eventId())) {
            return; // at-least-once delivery — redelivery of an already-processed event is a no-op
        }

        // approvalRequestId is always the transferId (TransferSubmissionService passes
        // transfer.getTransferId() as the engine's requestId, and the engine echoes it
        // back) — looking up by the transfer's own PK means the row is found from the
        // instant persistCreated commits, closing the window where an event could arrive
        // before markPendingApproval links approval_request_id.
        Transfer transfer = transfers.findById(event.requestId())
                .orElseThrow(() -> new TransferNotYetVisibleException(event.requestId()));

        if ("ApprovalCreationFailed".equals(event.eventType()) && transfer.getState() == TransferState.CREATED) {
            setState(transfer, TransferState.FAILED);
            notifications.notifyMaker(transfer.getMakerId(), transfer.getTransferId(),
                    "Your transfer submission could not be processed. Please contact support or try again.");
            markProcessed(event.eventId());
            return;
        }

        if (transfer.getState() == TransferState.CREATED) {
            // Event beat the local markPendingApproval commit — transient, not stale.
            // Do NOT mark processed: throwing here rolls back this transaction and the
            // controller returns non-2xx, so the relay's claim isn't marked published and
            // it retries in the next poll, by which point the link should exist.
            throw new TransferNotYetVisibleException(event.requestId());
        }

        if (transfer.getState() == TransferState.PENDING_APPROVAL) {
            switch (event.eventType()) {
                case "ApprovalApproved" -> {
                    releaseService.release(transfer);
                    notifications.notifyMaker(transfer.getMakerId(), transfer.getTransferId(),
                            "Your transfer was approved and is " + transfer.getState() + ".");
                }
                case "ApprovalRejected" -> {
                    setState(transfer, TransferState.REJECTED);
                    notifications.notifyMaker(transfer.getMakerId(), transfer.getTransferId(),
                            "Your transfer was rejected by a checker.");
                }
                case "ApprovalCancelled" -> setState(transfer, TransferState.CANCELLED); // maker's own action — no self-notification
                case "ApprovalExpired" -> {
                    setState(transfer, TransferState.EXPIRED);
                    notifications.notifyMaker(transfer.getMakerId(), transfer.getTransferId(),
                            "Your transfer expired without a decision within the approval SLA.");
                }
                case "ApprovalSubmitted" -> { /* no-op — transfer already PENDING_APPROVAL */ }
                default -> { /* unknown event type — ignore rather than fail the whole delivery */ }
            }
        }
        // else: transfer already moved past PENDING_APPROVAL (RELEASE_PENDING/RELEASED/
        // REJECTED/CANCELLED/EXPIRED) — a stale or duplicate-ish event on a settled transfer;
        // permanent no-op, mark processed below so it doesn't retry forever.

        markProcessed(event.eventId());
    }

    private void markProcessed(String eventId) {
        ProcessedEvent processed = new ProcessedEvent();
        processed.setEventId(eventId);
        processed.setProcessedAt(Instant.now());
        processedEvents.save(processed);
    }

    private void setState(Transfer transfer, TransferState state) {
        transfer.setState(state);
        transfers.save(transfer);
    }
}
