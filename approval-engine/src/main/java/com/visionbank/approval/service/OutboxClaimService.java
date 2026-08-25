package com.visionbank.approval.service;

import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxClaimService {

    private static final Duration STALE_CLAIM_AFTER = Duration.ofSeconds(30);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outbox;

    public OutboxClaimService(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    // Locks, claims, and releases the row lock within one short transaction —
    // no HTTP call happens while any row is locked. Safe for more than one
    // relay instance to run this concurrently: FOR UPDATE SKIP LOCKED means
    // two instances never claim the same row in the same pass.
    @Transactional
    public List<OutboxEvent> claimBatch() {
        List<String> ids = outbox.selectAndLockUnpublishedIds(Instant.now().minus(STALE_CLAIM_AFTER), BATCH_SIZE);
        if (ids.isEmpty()) {
            return List.of();
        }
        outbox.markClaimed(ids, Instant.now());
        return outbox.findAllById(ids);
    }

    @Transactional
    public void markPublished(String eventId) {
        outbox.findById(eventId).ifPresent(e -> {
            e.setPublishedAt(Instant.now());
            outbox.save(e);
        });
    }
}
