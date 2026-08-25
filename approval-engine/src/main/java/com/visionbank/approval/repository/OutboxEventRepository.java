package com.visionbank.approval.repository;

import com.visionbank.approval.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc();

    /**
     * Locks and returns the ids of a batch of unpublished, unclaimed (or
     * stale-claimed) events, skipping any row a concurrent relay instance
     * already has locked. Must be called inside the same short transaction
     * as markClaimed below — no HTTP call between them.
     */
    @Query(value = "SELECT event_id FROM outbox " +
                    "WHERE published_at IS NULL AND (claimed_at IS NULL OR claimed_at < :staleBefore) " +
                    "ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<String> selectAndLockUnpublishedIds(@Param("staleBefore") Instant staleBefore, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.claimedAt = :now WHERE o.eventId IN :ids")
    void markClaimed(@Param("ids") List<String> ids, @Param("now") Instant now);
}
