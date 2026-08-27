package com.visionbank.approval.service;

import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.messaging.ApprovalEvent;
import com.visionbank.approval.messaging.LifecycleEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxClaimService claimService;
    private final LifecycleEventPublisher publisher;

    public OutboxRelay(OutboxClaimService claimService, LifecycleEventPublisher publisher) {
        this.claimService = claimService;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 2000)
    public int relayOnce() {
        List<OutboxEvent> claimed = claimService.claimBatch();
        int published = 0;
        for (OutboxEvent event : claimed) {
            if (publish(event)) {
                claimService.markPublished(event.getEventId());
                published++;
            }
            // On failure, claimedAt stays set — it becomes reclaimable once
            // it's older than the claim service's stale-claim window, so a
            // crash mid-publish doesn't strand the event forever.
        }
        return published;
    }

    private boolean publish(OutboxEvent event) {
        try {
            publisher.publish(new ApprovalEvent(event.getEventId(), event.getEventType(), event.getRequestId(), event.getPayload()));
            return true;
        } catch (Exception e) {
            log.warn("Failed to relay event {} ({}): {}", event.getEventId(), event.getEventType(), e.getMessage());
            return false;
        }
    }
}
