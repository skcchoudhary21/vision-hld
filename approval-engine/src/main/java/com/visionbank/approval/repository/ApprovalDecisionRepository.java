package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, String> {
    long countByRequestIdAndDecisionAndState(String requestId, ApprovalDecision.DecisionType decision, String state);

    // Backs GET /approvals/{id}/workflow-view (Task 7): the current stage's individual
    // decisions, so a UI can render who's approved/rejected at this stage without any
    // hardcoded knowledge of the workflow's shape.
    List<ApprovalDecision> findByRequestIdAndState(String requestId, String state);

    // State-scoped: the PRIMARY idempotency check in approve() -- an actor's decision at one
    // stage must not block that same actor from deciding again at a LATER stage of the same
    // request (PrivilegedAccessWorkflowTest.sameActorCannotDoubleCountWithinOneStageButCanActAtALaterStage).
    boolean existsByRequestIdAndActorIdAndState(String requestId, String actorId, String state);

    // Request-wide, but scoped to the role the actor is retrying with: see approve()'s
    // eligibility-failure branch. A caller retrying approve() with the role that was valid
    // when they first acted, after the request has since moved past their stage to one
    // where that role no longer qualifies, must not get a hard 403 for a call that already
    // succeeded once. Requiring the role to match too (not just the actor id) means an
    // actor who genuinely never decided under THIS role still gets a clear signal instead of
    // a silent, misleading 200.
    boolean existsByRequestIdAndActorIdAndActorRole(String requestId, String actorId, String actorRole);
}
