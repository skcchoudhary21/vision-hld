package com.visionbank.approval.service;

import tools.jackson.databind.ObjectMapper;
import com.visionbank.approval.domain.*;
import com.visionbank.approval.repository.*;
import com.visionbank.approval.workflow.GuardContext;
import com.visionbank.approval.workflow.GuardRegistry;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

@Service
public class ApprovalCommandService {

    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;
    private final IdempotencyRecordRepository idempotency;
    private final WorkflowRegistry workflowRegistry;
    private final GuardRegistry guards;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalDecisionRepository decisions,
                                   AuditLogRepository audits, OutboxEventRepository outbox,
                                   IdempotencyRecordRepository idempotency, WorkflowRegistry workflowRegistry,
                                   GuardRegistry guards) {
        this.requests = requests;
        this.decisions = decisions;
        this.audits = audits;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.workflowRegistry = workflowRegistry;
        this.guards = guards;
    }

    // Every operation on an EXISTING request reads its own frozen copy -- never the
    // live registry. workflowRegistry is injected solely for create()'s one lookup.
    private WorkflowDefinition workflowFor(ApprovalRequest request) {
        return request.getPolicySnapshot().workflow();
    }

    private boolean isRoleAllowed(Transition transition, String actorRole) {
        return transition.allowedRoles().isEmpty() || transition.allowedRoles().contains(actorRole);
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
            // existing row's state/version/policy_snapshot via JPA's detached-entity save path --
            // reject rather than silently reset an in-flight or already-decided request.
            throw new IdempotencyConflictException(cmd.requestId());
        }

        WorkflowDefinition resolvedWorkflow;
        try {
            resolvedWorkflow = workflowRegistry.get(cmd.workflowId(), cmd.workflowVersion());
        } catch (IllegalStateException e) {
            throw new InvalidRequestException("Unknown workflow " + cmd.workflowId() + ":" + cmd.workflowVersion());
        }

        PolicySnapshot policy = new PolicySnapshot(cmd.policyVersion(), resolvedWorkflow);

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId(cmd.requestId());
        request.setRequestType(cmd.requestType());
        request.setWorkflowId(resolvedWorkflow.name());
        request.setWorkflowVersion(resolvedWorkflow.version());
        request.setMakerId(cmd.makerId());
        request.setPolicySnapshot(policy);
        request.setPayload(cmd.payloadJson());
        request.setCreatedAt(Instant.now());
        request.setExpiresAt(cmd.expiresAt());
        request.setVersion(0L);
        request.setState("SUBMITTED");

        // Each workflow's SUBMITTED state now has exactly one outgoing transition (spec
        // §3.5 -- quorum is fixed per workflow version, so there's nothing left to branch
        // on per-request). Still evaluated generically via guards -- an empty guards list
        // always passes -- so a future workflow that DOES want conditional routing on
        // something guard-worthy (not required-approvals-count) still works unmodified.
        GuardContext ctx = new GuardContext(cmd.makerId(), 0, null, null, false, "SUBMITTED", null);
        Transition initial = resolvedWorkflow.transitionsFrom("SUBMITTED").stream()
                .filter(t -> t.guards().stream().allMatch(g -> guards.get(g).evaluate(ctx)))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        "No transition from SUBMITTED satisfied for workflow " + resolvedWorkflow.name()));

        request.setState(initial.to());
        request.setVersion(1L);
        requests.save(request);

        writeAudit(cmd.requestId(), null, null, "SUBMITTED", "SUBMITTED", initial.to());
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
            throw classifyRaceOrIllegal(requestId, workflow, currentState, request.getVersion(), "approve");
        }

        if (!isRoleAllowed(transition, actorRole)) {
            // Ineligible for the CURRENT stage doesn't necessarily mean this call is bogus: a
            // client that already legitimately decided at an earlier stage may retry with the
            // (now stale) role it used back then, after the request has since moved on to a
            // stage that role doesn't qualify for. Treat that specific case as a harmless
            // stale replay rather than a hard Forbidden; an actor with no decision anywhere on
            // this request is still genuinely rejected.
            if (decisions.existsByRequestIdAndActorIdAndActorRole(requestId, actorId, actorRole)) {
                return toView(request);
            }
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        GuardContext preDecisionCtx = new GuardContext(request.getMakerId(), 0, actorId, actorRole, false,
                currentState, transition.requiredApprovals());
        for (String guardName : transition.guards()) {
            if (guardName.equals("approvals_satisfied")) {
                continue; // evaluated after recording the decision, with the real count
            }
            if (!guards.get(guardName).evaluate(preDecisionCtx)) {
                throw new ForbiddenActionException("Maker cannot approve their own request: " + requestId);
            }
        }

        if (decisions.existsByRequestIdAndActorIdAndState(requestId, actorId, currentState)) {
            return toView(request); // already decided at this stage -- idempotent replay of the decision itself
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

        if (transition.guards().contains("approvals_satisfied")) {
            GuardContext quorumCtx = new GuardContext(request.getMakerId(), approvalCount, actorId, actorRole, false,
                    currentState, transition.requiredApprovals());
            if (!guards.get("approvals_satisfied").evaluate(quorumCtx)) {
                writeAudit(requestId, actorId, actorRole, "APPROVAL_RECORDED", currentState, currentState);
                return toView(request);
            }
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, workflow, latest.getState(), latest.getVersion(), "approve");
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

        // Checked before the transition lookup, not after: reject is terminal, so by the time
        // a retry from the SAME actor lands, currentState has already moved to REJECTED and
        // there's no outgoing "reject" transition from there to even find -- without this
        // check the retry would fall into classifyRaceOrIllegal and get a spurious 409
        // CONCURRENT_STATE_CHANGE instead of replaying the decision it already made.
        if (decisions.existsByRequestIdAndActorIdAndDecision(requestId, actorId, ApprovalDecision.DecisionType.REJECT)) {
            return toView(request);
        }

        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("reject"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            throw classifyRaceOrIllegal(requestId, workflow, currentState, request.getVersion(), "reject");
        }

        if (!isRoleAllowed(transition, actorRole)) {
            throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
        }

        GuardContext ctx = new GuardContext(request.getMakerId(), 0, actorId, actorRole, false,
                currentState, transition.requiredApprovals());
        for (String guardName : transition.guards()) {
            if (!guards.get(guardName).evaluate(ctx)) {
                throw new ForbiddenActionException("Actor role " + actorRole + " is not an eligible checker for " + requestId);
            }
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, workflow, latest.getState(), latest.getVersion(), "reject");
        }

        ApprovalDecision decision = new ApprovalDecision();
        decision.setRequestId(requestId);
        decision.setActorId(actorId);
        decision.setActorRole(actorRole);
        decision.setState(currentState);
        decision.setDecision(ApprovalDecision.DecisionType.REJECT);
        decision.setCreatedAt(Instant.now());
        decisions.save(decision);

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
            throw classifyRaceOrIllegal(requestId, workflow, currentState, request.getVersion(), "cancel");
        }

        GuardContext ctx = new GuardContext(request.getMakerId(), 0, actorId, "MAKER", false,
                currentState, transition.requiredApprovals());
        for (String guardName : transition.guards()) {
            if (!guards.get(guardName).evaluate(ctx)) {
                throw new ForbiddenActionException("Only the maker can cancel request " + requestId);
            }
        }

        int rows = requests.guardedTransition(requestId, currentState, request.getVersion(), transition.to());
        if (rows == 0) {
            ApprovalRequest latest = requests.findByRequestId(requestId).orElseThrow();
            throw classifyRaceOrIllegal(requestId, workflow, latest.getState(), latest.getVersion(), "cancel");
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
    // aggregate, not a single-row transition -- the guarded UPDATE alone can't
    // protect it. Taking the lock here serializes concurrent commands on the
    // same request, so the second one to run always sees the first's
    // committed decision before deciding whether quorum is met.
    private ApprovalRequest loadOrThrow(String requestId) {
        return requests.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(requestId));
    }

    /**
     * A "lost race" (409 CONCURRENT_STATE_CHANGE) is a `current` state reachable from some
     * state `action` could have started from -- the action was legal when presumably read,
     * just lost the race. Otherwise it's 409 INVALID_STATE_TRANSITION. Reachability is BFS
     * shortest-path over the workflow's own graph, not hardcoded to any one workflow's shape.
     * When a shortcut to `current` bypasses `action`'s stage entirely, currentVersion (hops
     * actually taken on this row) disambiguates: matching the shortcut's length means this
     * row took the shortcut and never saw `action`'s stage (illegal); anything else is a race.
     */
    private RuntimeException classifyRaceOrIllegal(String requestId, WorkflowDefinition workflow, String current,
                                                     long currentVersion, String action) {
        List<String> candidateStarts = workflow.transitions().stream()
                .filter(t -> t.name().equals(action))
                .map(Transition::from)
                .distinct()
                .toList();

        if (candidateStarts.isEmpty()) {
            return new InvalidStateTransitionException(requestId, current, action);
        }

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

        int shortestFromInitialState = shortestPathLength(workflow, workflow.initialState(), current);

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
        Queue<String> frontier = new ArrayDeque<>();
        HashMap<String, Integer> distance = new HashMap<>();
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
