package com.visionbank.approval.service;

import tools.jackson.databind.ObjectMapper;
import com.visionbank.approval.domain.*;
import com.visionbank.approval.repository.*;
import com.visionbank.approval.workflow.GuardContext;
import com.visionbank.approval.workflow.GuardRegistry;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import com.visionbank.approval.workflow.WorkflowRegistry;
import com.visionbank.approval.workflow.WorkflowSelector;
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
    private final WorkflowRegistry workflowRegistry;
    private final WorkflowSelector workflowSelector;
    private final GuardRegistry guards;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalDecisionRepository decisions,
                                   AuditLogRepository audits, OutboxEventRepository outbox,
                                   IdempotencyRecordRepository idempotency, WorkflowRegistry workflowRegistry,
                                   WorkflowSelector workflowSelector, GuardRegistry guards) {
        this.requests = requests;
        this.decisions = decisions;
        this.audits = audits;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.workflowRegistry = workflowRegistry;
        this.workflowSelector = workflowSelector;
        this.guards = guards;
    }

    private WorkflowDefinition workflowFor(ApprovalRequest request) {
        return workflowRegistry.get(request.getWorkflowId());
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

        WorkflowDefinition resolvedWorkflow = workflowSelector.resolve(cmd.requestType());

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId(cmd.requestId());
        request.setRequestType(cmd.requestType());
        request.setWorkflowId(resolvedWorkflow.name());
        request.setWorkflowVersion(resolvedWorkflow.version());
        request.setMakerId(cmd.makerId());
        request.setPolicySnapshot(cmd.policy());
        request.setPayload(cmd.payloadJson());
        request.setCreatedAt(Instant.now());
        request.setExpiresAt(cmd.expiresAt());
        request.setVersion(0L);
        request.setState("SUBMITTED");

        // currentState must be the workflow's actual approval-gate state, not literally
        // "SUBMITTED": the no_approval_required/approval_required guards resolve their
        // StagePolicy via ctx.currentState(), and PolicySnapshot.stages() only ever carries
        // an entry for that gate state -- SUBMITTED itself is never a keyed stage. Every
        // transition out of SUBMITTED is evaluated under the SAME gate state (transfer-approval's
        // auto_approve and require_approval both key off "is approval required at this one
        // gate", they just disagree on the answer) -- that gate is the workflow's one
        // non-terminal destination from SUBMITTED (PENDING_APPROVAL for transfer-approval,
        // SECURITY_REVIEW for privileged-access); a purely-terminal destination (like
        // transfer-approval's auto_approve -> APPROVED) is never itself the gate.
        String gateState = resolvedWorkflow.transitionsFrom("SUBMITTED").stream()
                .map(Transition::to)
                .filter(s -> !resolvedWorkflow.isTerminal(s))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow " + resolvedWorkflow.name() + " has no non-terminal transition from SUBMITTED"));
        GuardContext ctx = new GuardContext(cmd.makerId(), cmd.policy(), 0, null, null, false, gateState);
        Transition initial = resolvedWorkflow.transitionsFrom("SUBMITTED").stream()
                .filter(t -> guards.get(t.guard()).evaluate(ctx))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No transition from SUBMITTED satisfied by policy"));

        request.setState(initial.to());
        request.setVersion(1L);
        requests.save(request);

        writeAudit(cmd.requestId(), null, null, "SUBMITTED", "SUBMITTED", initial.to());
        // ApprovalSubmitted always fires on create, regardless of workflow shape: every
        // workflow's SUBMITTED state is non-terminal, so it's never itself in any workflow's
        // events: map and fireEvents(initial.to()) below can never emit it a second time.
        // Auto-approved requests then additionally emit ApprovalApproved (spec §16) so
        // Transfer's release trigger is always "on ApprovalApproved" -- no separate
        // auto-release path; fireEvents(resolvedWorkflow, ..., initial.to()) covers that
        // via transfer-approval's events: map exactly as before.
        writeOutbox(cmd.requestId(), "ApprovalSubmitted");
        fireEvents(resolvedWorkflow, cmd.requestId(), initial.to());

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
        WorkflowDefinition workflow = workflowFor(request);
        String currentState = request.getState();

        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("approve"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            throw classifyRaceOrIllegal(requestId, currentState, request.getVersion(), "approve");
        }

        GuardContext eligibility = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0,
                actorId, actorRole, false, currentState);
        if (guards.get("actor_is_maker").evaluate(eligibility) && !request.getPolicySnapshot().makerCanApprove()) {
            throw new ForbiddenActionException("Maker cannot approve their own request: " + requestId);
        }
        if (!guards.get("actor_is_eligible_checker").evaluate(eligibility)) {
            // Ineligible for the CURRENT stage doesn't necessarily mean this call is bogus: a
            // client that already legitimately decided at an earlier stage may retry with the
            // (now stale) role it used back then, after the request has since moved on to a
            // stage that role doesn't qualify for. Treat that specific case as a harmless
            // stale replay rather than a hard Forbidden; an actor with no decision anywhere on
            // this request is still genuinely rejected.
            if (decisions.existsByRequestIdAndActorId(requestId, actorId)) {
                return toView(request);
            }
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        if (decisions.existsByRequestIdAndActorIdAndState(requestId, actorId, currentState)) {
            return toView(request); // already decided at this stage — idempotent replay of the decision itself
        }

        ApprovalDecision decision = new ApprovalDecision();
        decision.setRequestId(requestId);
        decision.setActorId(actorId);
        decision.setActorRole(actorRole);
        decision.setState(currentState);
        decision.setDecision(ApprovalDecision.DecisionType.APPROVE);
        decision.setCreatedAt(Instant.now());
        decisions.save(decision);

        long approvalCount = decisions.countByRequestIdAndDecisionAndState(requestId, ApprovalDecision.DecisionType.APPROVE, currentState);
        GuardContext quorumCtx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), approvalCount,
                actorId, actorRole, false, currentState);

        if (!guards.get(transition.guard()).evaluate(quorumCtx)) {
            writeAudit(requestId, actorId, actorRole, "APPROVAL_RECORDED", currentState, currentState);
            return toView(request);
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, latest.getState(), latest.getVersion(), "approve");
        }

        writeAudit(requestId, actorId, actorRole, "APPROVED", currentState, transition.to());
        fireEvents(workflow, requestId, transition.to());
        return new ApprovalRequestView(requestId, transition.to(), request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView reject(String requestId, String actorId, String actorRole) {
        ApprovalRequest request = loadOrThrow(requestId);
        WorkflowDefinition workflow = workflowFor(request);
        String currentState = request.getState();

        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("reject"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            throw classifyRaceOrIllegal(requestId, currentState, request.getVersion(), "reject");
        }

        GuardContext ctx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0, actorId, actorRole, false, currentState);
        if (!guards.get("actor_is_eligible_checker").evaluate(ctx)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, latest.getState(), latest.getVersion(), "reject");
        }
        writeAudit(requestId, actorId, actorRole, "REJECTED", currentState, transition.to());
        fireEvents(workflow, requestId, transition.to());
        return new ApprovalRequestView(requestId, transition.to(), request.getVersion() + 1);
    }

    @Transactional
    public ApprovalRequestView cancel(String requestId, String actorId) {
        ApprovalRequest request = loadOrThrow(requestId);
        WorkflowDefinition workflow = workflowFor(request);
        String currentState = request.getState();

        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("cancel"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            throw classifyRaceOrIllegal(requestId, currentState, request.getVersion(), "cancel");
        }

        GuardContext ctx = new GuardContext(request.getMakerId(), request.getPolicySnapshot(), 0, actorId, "MAKER", false, currentState);
        if (!guards.get("actor_is_maker").evaluate(ctx)) {
            throw new ForbiddenActionException("Only the maker can cancel request " + requestId);
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, latest.getState(), latest.getVersion(), "cancel");
        }
        writeAudit(requestId, actorId, "MAKER", "CANCELLED", currentState, transition.to());
        fireEvents(workflow, requestId, transition.to());
        return new ApprovalRequestView(requestId, transition.to(), request.getVersion() + 1);
    }

    private void fireEvents(WorkflowDefinition workflow, String requestId, String state) {
        for (String eventType : workflow.eventsFor(state)) {
            writeOutbox(requestId, eventType);
        }
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
     * A current state is a "lost race" (409 CONCURRENT_STATE_CHANGE) if the actor's
     * target action was legal at the moment they presumably read the row -- i.e. `current`
     * is graph-reachable from some state that `action` could plausibly have started from,
     * via zero or more further legal transitions. Otherwise the action could never have
     * applied to this row regardless of timing (409 INVALID_STATE_TRANSITION).
     *
     * Reachability is computed via real BFS shortest-path over the request's own resolved
     * WorkflowDefinition, not hardcoded to any particular workflow's shape (see spec §7 /
     * Task 6 brief). When more than one candidate start reaches `current`, and at different
     * hop counts, currentVersion (the number of transitions actually taken on this row)
     * disambiguates a genuine multi-step race from a shortcut path that never involved a
     * real decision at the actor's target stage.
     */
    private RuntimeException classifyRaceOrIllegal(String requestId, String current, long currentVersion, String action) {
        WorkflowDefinition workflow = workflowRegistry.get(requests.findByRequestId(requestId).orElseThrow().getWorkflowId());

        // Every state this action-type could plausibly have started from: any `from`
        // of a transition named `action`, anywhere in the workflow. If none exist at
        // all, the action never applies to this workflow regardless of state or timing.
        java.util.List<String> candidateStarts = workflow.transitions().stream()
                .filter(t -> t.name().equals(action))
                .map(Transition::from)
                .distinct()
                .toList();

        if (candidateStarts.isEmpty()) {
            return new InvalidStateTransitionException(requestId, current, action);
        }

        // Shortest total path (in transitions, from the workflow's true initialState) that
        // passes through some state where `action` was actually available: hops to reach a
        // candidate start, plus hops from there on to `current`.
        int shortestViaCandidateStart = Integer.MAX_VALUE;
        boolean reachableViaCandidateStart = false;
        for (String start : candidateStarts) {
            int hopsToStart = shortestPathLength(workflow, workflow.initialState(), start);
            int hopsFromStart = shortestPathLength(workflow, start, current);
            if (hopsToStart >= 0 && hopsFromStart >= 0) {
                reachableViaCandidateStart = true;
                shortestViaCandidateStart = Math.min(shortestViaCandidateStart, hopsToStart + hopsFromStart);
            }
        }

        if (!reachableViaCandidateStart) {
            return new InvalidStateTransitionException(requestId, current, action);
        }

        // Shortest path from the workflow's true initialState to `current` via ANY
        // transitions at all, regardless of name -- catches shortcuts like transfer-approval's
        // auto_approve, which reach a shared terminal state without ever passing through
        // `action`'s own stage.
        int shortestFromInitialState = shortestPathLength(workflow, workflow.initialState(), current);

        // A strictly shorter shortcut bypasses `action`'s stage entirely: ambiguous. currentVersion
        // is exactly the number of transitions actually taken on this row -- if it matches the
        // shortcut's length precisely, this row took the shortcut and never passed through a state
        // where `action` applied, regardless of when this call arrived (illegal). Any other version
        // means real decisions happened beyond the shortcut -- a genuine race. When no such shortcut
        // exists (every path to `current` passes through a candidate start), there's nothing to
        // disambiguate and it's a legitimate race regardless of version.
        if (shortestFromInitialState >= 0 && shortestFromInitialState < shortestViaCandidateStart) {
            return currentVersion == shortestFromInitialState
                    ? new InvalidStateTransitionException(requestId, current, action)
                    : new ConcurrentStateChangeException(requestId, current);
        }
        return new ConcurrentStateChangeException(requestId, current);
    }

    private int shortestPathLength(WorkflowDefinition workflow, String from, String to) {
        if (from.equals(to)) {
            return 0;
        }
        java.util.Queue<String> frontier = new java.util.ArrayDeque<>();
        java.util.Map<String, Integer> distance = new java.util.HashMap<>();
        frontier.add(from);
        distance.put(from, 0);
        while (!frontier.isEmpty()) {
            String node = frontier.poll();
            for (Transition t : workflow.transitionsFrom(node)) {
                if (!distance.containsKey(t.to())) {
                    distance.put(t.to(), distance.get(node) + 1);
                    if (t.to().equals(to)) {
                        return distance.get(t.to());
                    }
                    frontier.add(t.to());
                }
            }
        }
        return -1;
    }

    private void writeAudit(String requestId, String actorId, String actorRole, String action,
                             String from, String to) {
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
