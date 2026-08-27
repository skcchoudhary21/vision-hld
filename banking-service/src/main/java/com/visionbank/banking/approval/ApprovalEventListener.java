package com.visionbank.banking.approval;

import com.visionbank.banking.domain.ProcessedEvent;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.notification.NotificationClient;
import com.visionbank.banking.repository.ProcessedEventRepository;
import com.visionbank.banking.repository.TransferRepository;
import com.visionbank.banking.service.ReleaseService;
import com.visionbank.banking.service.TransferPersistenceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class ApprovalEventListener {

    private final TransferRepository transfers;
    private final ProcessedEventRepository processedEvents;
    private final ReleaseService releaseService;
    private final NotificationClient notifications;
    private final TransferPersistenceService persistenceService;

    public ApprovalEventListener(TransferRepository transfers, ProcessedEventRepository processedEvents,
                                  ReleaseService releaseService, NotificationClient notifications,
                                  TransferPersistenceService persistenceService) {
        this.transfers = transfers;
        this.processedEvents = processedEvents;
        this.releaseService = releaseService;
        this.notifications = notifications;
        this.persistenceService = persistenceService;
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

        // CREATED: the normal pre-link window. FAILED: a transfer whose earlier creation
        // attempt gave up (SubmissionCommandReconciler.giveUp() -> ApprovalCreationFailed)
        // and was then resumed with the same Idempotency-Key (TransferSubmissionService
        // .resumeIfNeeded() republishes unconditionally from FAILED) -- approval-engine
        // creates a brand-new live workflow for the resumed attempt, and this is where
        // banking-service must pick that link back up. Without FAILED matching here, the
        // resumed workflow runs to completion on approval-engine's side while every event
        // for it is silently discarded below (markProcessed, no state change) -- the two
        // services diverge permanently with nothing surfaced anywhere. Neither
        // markPendingApproval nor setState has a from-state guard, so re-linking from
        // FAILED is exactly as safe as linking from CREATED.
        if (transfer.getState() == TransferState.CREATED || transfer.getState() == TransferState.FAILED) {
            if ("ApprovalCreationFailed".equals(event.eventType())) {
                setState(transfer, TransferState.FAILED);
                notifications.notifyMaker(transfer.getMakerId(), transfer.getTransferId(),
                        "Your transfer submission could not be processed. Please contact support or try again.");
                markProcessed(event.eventId());
                return;
            }
            // Any other event type is the workflow-creation signal itself (ApprovalSubmitted,
            // always written first in ApprovalCommandService.doCreate()) or, in principle, a
            // later event that outran it -- either way, this is the first proof banking-service
            // has that a workflow now exists (whether this is the original attempt or a
            // resumed one). Link now; the guarded UPDATE this mirrors on approval-engine's
            // side has no equivalent here since there's only one row to update, not a race
            // between callers.
            persistenceService.markPendingApproval(transfer.getTransferId(), event.requestId());
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
                case "ApprovalSubmitted" -> { /* the linking event itself (handled above), or a harmless redelivery of it after already linked */ }
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
