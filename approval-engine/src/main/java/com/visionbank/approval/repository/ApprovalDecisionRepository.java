package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, String> {
    long countByRequestIdAndDecisionAndState(String requestId, ApprovalDecision.DecisionType decision, String state);

    // State-scoped: the PRIMARY idempotency check in approve() -- an actor's decision at one
    // stage must not block that same actor from deciding again at a LATER stage of the same
    // request (PrivilegedAccessWorkflowTest.sameActorCannotDoubleCountWithinOneStageButCanActAtALaterStage).
    boolean existsByRequestIdAndActorIdAndState(String requestId, String actorId, String state);

    // Request-wide: NOT a duplicate of the above (kept deliberately, still has a real call
    // site -- see approve()'s eligibility-failure branch). A caller retrying approve() with
    // the role that was valid when they first acted, after the request has since moved past
    // their stage to one where that role no longer qualifies, must not get a hard 403 for a
    // call that already succeeded once; some prior decision by this actor on this request
    // (regardless of which state it was recorded under) is what tells approve() this is a
    // stale-but-harmless replay rather than a genuinely ineligible actor.
    boolean existsByRequestIdAndActorId(String requestId, String actorId);
}
