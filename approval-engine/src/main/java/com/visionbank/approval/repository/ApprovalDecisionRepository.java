package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, String> {
    long countByRequestIdAndDecisionAndState(String requestId, ApprovalDecision.DecisionType decision, String state);
    boolean existsByRequestIdAndActorId(String requestId, String actorId);
}
