package com.visionbank.approval.service;

import tools.jackson.databind.ObjectMapper;
import com.visionbank.approval.domain.*;
import com.visionbank.approval.repository.*;
import com.visionbank.approval.workflow.GuardContext;
import com.visionbank.approval.workflow.GuardRegistry;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class ApprovalCommandService {

    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;
    private final IdempotencyRecordRepository idempotency;
    private final WorkflowDefinition workflow;
    private final GuardRegistry guards;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalDecisionRepository decisions,
                                   AuditLogRepository audits, OutboxEventRepository outbox,
                                   IdempotencyRecordRepository idempotency, WorkflowDefinition workflow,
                                   GuardRegistry guards) {
        this.requests = requests;
        this.decisions = decisions;
        this.audits = audits;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.workflow = workflow;
        this.guards = guards;
    }

    @Transactional
    public ApprovalRequestView create(CreateApprovalRequest cmd, String idempotencyKey) {
        String hash = hash(cmd);
        Optional<IdempotencyRecord> existing = idempotency.findById(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            ApprovalRequest replayed = requests.findByRequestId(existing.get().getRequestId()).orElseThrow();
            return toView(replayed);
        }

        if (requests.findByRequestId(cmd.requestId()).isPresent()) {
            // Same requestId under a fresh idempotency key would otherwise merge-overwrite the
            // existing row's state/version/policy_snapshot via JPA's detached-entity save path —
            // reject rather than silently reset an in-flight or already-decided request.
            throw new IdempotencyConflictException(cmd.requestId());
        }

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId(cmd.requestId());
        request.setRequestType(cmd.requestType());
        request.setMakerId(cmd.makerId());
        request.setPolicySnapshot(cmd.policy());
        request.setPayload(cmd.payloadJson());
        request.setCreatedAt(Instant.now());
        request.setExpiresAt(cmd.expiresAt());
        request.setVersion(0L);
        request.setState(ApprovalState.SUBMITTED);

        GuardContext ctx = new GuardContext(cmd.makerId(), cmd.policy(), 0, null, null, false);
        Transition initial = workflow.transitionsFrom(ApprovalState.SUBMITTED).stream()
                .filter(t -> guards.get(t.guard()).evaluate(ctx))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No transition from SUBMITTED satisfied by policy"));

        request.setState(initial.to());
        request.setVersion(1L);
        requests.save(request);

        writeAudit(cmd.requestId(), null, null, "SUBMITTED", ApprovalState.SUBMITTED, initial.to());
        // Auto-approved requests emit BOTH events (spec §16) so Transfer's release
        // trigger is always "on ApprovalApproved" — no separate auto-release path.
        writeOutbox(cmd.requestId(), "ApprovalSubmitted");
        if (initial.to() == ApprovalState.APPROVED) {
            writeOutbox(cmd.requestId(), "ApprovalApproved");
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setKey(idempotencyKey);
        record.setCommandType("CREATE");
        record.setRequestId(cmd.requestId());
        record.setRequestHash(hash);
        record.setResult("{\"state\":\"" + initial.to() + "\"}");
        record.setCreatedAt(Instant.now());
        idempotency.save(record);

        return toView(request);
    }

    @Transactional
    public ApprovalRequestView approve(String requestId, String actorId, String actorRole) {
        ApprovalRequest request = loadOrThrow(requestId);

        if (request.getState() != ApprovalState.PENDING_APPROVAL) {
            throw classifyRaceOrIllegal(requestId, request.getState(), request.getVersion(), "approve");
        }

        GuardContext eligibility = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0,
                actorId, actorRole, false);
        if (guards.get("actor_is_maker").evaluate(eligibility) && !request.getPolicySnapshot().makerCanApprove()) {
            throw new ForbiddenActionException("Maker cannot approve their own request: " + requestId);
        }
        if (!guards.get("actor_is_eligible_checker").evaluate(eligibility)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        if (decisions.existsByRequestIdAndActorId(requestId, actorId)) {
            return toView(request); // already decided — idempotent replay of the decision itself
        }

        ApprovalDecision decision = new ApprovalDecision();
        decision.setRequestId(requestId);
        decision.setActorId(actorId);
        decision.setActorRole(actorRole);
        decision.setDecision(ApprovalDecision.DecisionType.APPROVE);
        decision.setCreatedAt(Instant.now());
        decisions.save(decision);

        long approvalCount = decisions.countByRequestIdAndDecision(requestId, ApprovalDecision.DecisionType.APPROVE);
        GuardContext quorumCtx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), approvalCount,
                actorId, actorRole, false);

        if (!guards.get("approvals_satisfied").evaluate(quorumCtx)) {
            writeAudit(requestId, actorId, actorRole, "APPROVAL_RECORDED", ApprovalState.PENDING_APPROVAL, ApprovalState.PENDING_APPROVAL);
            return toView(request);
        }

        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, request.getVersion(), ApprovalState.APPROVED);
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, latest.getState(), latest.getVersion(), "approve");
        }

        writeAudit(requestId, actorId, actorRole, "APPROVED", ApprovalState.PENDING_APPROVAL, ApprovalState.APPROVED);
        writeOutbox(requestId, "ApprovalApproved");
        return new ApprovalRequestView(requestId, ApprovalState.APPROVED, request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView reject(String requestId, String actorId, String actorRole) {
        ApprovalRequest request = loadOrThrow(requestId);
        if (request.getState() != ApprovalState.PENDING_APPROVAL) {
            throw classifyRaceOrIllegal(requestId, request.getState(), request.getVersion(), "reject");
        }
        GuardContext ctx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0, actorId, actorRole, false);
        if (!guards.get("actor_is_eligible_checker").evaluate(ctx)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, request.getVersion(), ApprovalState.REJECTED);
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, latest.getState(), latest.getVersion(), "reject");
        }
        writeAudit(requestId, actorId, actorRole, "REJECTED", ApprovalState.PENDING_APPROVAL, ApprovalState.REJECTED);
        writeOutbox(requestId, "ApprovalRejected");
        return new ApprovalRequestView(requestId, ApprovalState.REJECTED, request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView cancel(String requestId, String actorId) {
        ApprovalRequest request = loadOrThrow(requestId);
        if (request.getState() != ApprovalState.PENDING_APPROVAL) {
            throw classifyRaceOrIllegal(requestId, request.getState(), request.getVersion(), "cancel");
        }
        GuardContext ctx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0, actorId, "MAKER", false);
        if (!guards.get("actor_is_maker").evaluate(ctx)) {
            throw new ForbiddenActionException("Only the maker can cancel request " + requestId);
        }

        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, request.getVersion(), ApprovalState.CANCELLED);
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, latest.getState(), latest.getVersion(), "cancel");
        }
        writeAudit(requestId, actorId, "MAKER", "CANCELLED", ApprovalState.PENDING_APPROVAL, ApprovalState.CANCELLED);
        writeOutbox(requestId, "ApprovalCancelled");
        return new ApprovalRequestView(requestId, ApprovalState.CANCELLED, request.getVersion() + 1);
    }

    // Row-locking on purpose (spec §12 amendment): approve/reject/cancel all
    // read-then-maybe-transition based on a decision COUNT, which is an
    // aggregate, not a single-row transition — the guarded UPDATE alone can't
    // protect it. Taking the lock here serializes concurrent commands on the
    // same request, so the second one to run always sees the first's
    // committed decision before deciding whether quorum is met.
    private ApprovalRequest loadOrThrow(String requestId) {
        return requests.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(requestId));
    }

    /**
     * A current state is a "lost race" (409 CONCURRENT_STATE_CHANGE) if it's reachable
     * from PENDING_APPROVAL per the workflow definition — some other legal command won.
     * Otherwise the action was never legal from this state, regardless of timing
     * (409 INVALID_STATE_TRANSITION) — e.g. approve() on a request that auto-approved
     * straight from SUBMITTED and never passed through PENDING_APPROVAL.
     *
     * APPROVED is reachable both ways (auto_approve from SUBMITTED, and approve from
     * PENDING_APPROVAL), so state alone can't tell those two apart; currentVersion
     * breaks the tie. create() always stamps version 1 on a request's first transition,
     * and only a later real PENDING_APPROVAL transition bumps it again, so version 1
     * here means this row auto-approved and never passed through PENDING_APPROVAL.
     */
    private RuntimeException classifyRaceOrIllegal(String requestId, ApprovalState current, long currentVersion, String action) {
        if (current == ApprovalState.PENDING_APPROVAL) {
            return new ConcurrentStateChangeException(requestId, current);
        }
        boolean reachableFromPendingApproval = workflow.transitionsFrom(ApprovalState.PENDING_APPROVAL).stream()
                .anyMatch(t -> t.to() == current);
        boolean reachableFromSubmitted = workflow.transitionsFrom(ApprovalState.SUBMITTED).stream()
                .anyMatch(t -> t.to() == current);
        if (reachableFromPendingApproval && reachableFromSubmitted) {
            return currentVersion <= 1
                    ? new InvalidStateTransitionException(requestId, current, action)
                    : new ConcurrentStateChangeException(requestId, current);
        }
        if (reachableFromPendingApproval) {
            return new ConcurrentStateChangeException(requestId, current);
        }
        return new InvalidStateTransitionException(requestId, current, action);
    }

    private void writeAudit(String requestId, String actorId, String actorRole, String action,
                             ApprovalState from, ApprovalState to) {
        AuditLog log = new AuditLog();
        log.setRequestId(requestId);
        log.setActorId(actorId);
        log.setActorRole(actorRole);
        log.setAction(action);
        log.setPreviousState(from);
        log.setNewState(to);
        log.setCreatedAt(Instant.now());
        audits.save(log);
    }

    private void writeOutbox(String requestId, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setRequestId(requestId);
        event.setEventType(eventType);
        event.setEventVersion(1);
        event.setPayload("{\"requestId\":\"" + requestId + "\",\"eventType\":\"" + eventType + "\"}");
        event.setCreatedAt(Instant.now());
        outbox.save(event);
    }

    private ApprovalRequestView toView(ApprovalRequest r) {
        return new ApprovalRequestView(r.getRequestId(), r.getState(), r.getVersion());
    }

    private String hash(CreateApprovalRequest cmd) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(mapper.writeValueAsBytes(cmd));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
