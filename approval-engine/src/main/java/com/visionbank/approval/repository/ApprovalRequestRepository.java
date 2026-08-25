package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.ApprovalState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    Optional<ApprovalRequest> findByRequestId(String requestId);

    /**
     * The single concurrency mechanism for every transition in the engine.
     * Returns 1 if this call won the race, 0 if the state/version had already
     * moved (lost race or illegal transition — caller re-reads to distinguish).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ApprovalRequest a SET a.state = :newState, a.version = a.version + 1 " +
           "WHERE a.requestId = :requestId AND a.state = :expectedState AND a.version = :expectedVersion")
    int guardedTransition(@Param("requestId") String requestId,
                           @Param("expectedState") ApprovalState expectedState,
                           @Param("expectedVersion") long expectedVersion,
                           @Param("newState") ApprovalState newState);

    List<ApprovalRequest> findByStateAndExpiresAtBefore(ApprovalState state, Instant cutoff);

    /**
     * Row lock for approve/reject/cancel (Task 5), taken before counting
     * decisions. Quorum counting is an aggregate read, not a single-row
     * transition — without this lock, two concurrent approvers can each
     * undercount (neither sees the other's uncommitted decision) and both
     * skip the transition, stranding a quorum-satisfied request forever.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ApprovalRequest a WHERE a.requestId = :requestId")
    Optional<ApprovalRequest> findByRequestIdForUpdate(@Param("requestId") String requestId);
}
