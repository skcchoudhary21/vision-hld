package com.visionbank.transfer.approval;

import com.visionbank.transfer.domain.ProcessedEvent;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.ProcessedEventRepository;
import com.visionbank.transfer.repository.TransferRepository;
import com.visionbank.transfer.service.ReleaseService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class ApprovalEventListener {

    private final TransferRepository transfers;
    private final ProcessedEventRepository processedEvents;
    private final ReleaseService releaseService;

    public ApprovalEventListener(TransferRepository transfers, ProcessedEventRepository processedEvents,
                                  ReleaseService releaseService) {
        this.transfers = transfers;
        this.processedEvents = processedEvents;
        this.releaseService = releaseService;
    }

    @Transactional
    public void handle(IncomingEvent event) {
        if (processedEvents.existsById(event.eventId())) {
            return; // at-least-once delivery — redelivery of an already-processed event is a no-op
        }

        transfers.findByApprovalRequestId(event.requestId()).ifPresent(transfer -> {
            switch (event.eventType()) {
                case "ApprovalApproved" -> releaseService.release(transfer);
                case "ApprovalRejected" -> setState(transfer, TransferState.REJECTED);
                case "ApprovalCancelled" -> setState(transfer, TransferState.CANCELLED);
                case "ApprovalExpired" -> setState(transfer, TransferState.EXPIRED);
                case "ApprovalSubmitted" -> { /* no-op — transfer already WAITING_FOR_APPROVAL */ }
                default -> { /* unknown event type — ignore rather than fail the whole delivery */ }
            }
        });

        ProcessedEvent processed = new ProcessedEvent();
        processed.setEventId(event.eventId());
        processed.setProcessedAt(Instant.now());
        processedEvents.save(processed);
    }

    private void setState(Transfer transfer, TransferState state) {
        transfer.setState(state);
        transfers.save(transfer);
    }
}
