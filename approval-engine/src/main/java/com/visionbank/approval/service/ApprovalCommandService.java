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
