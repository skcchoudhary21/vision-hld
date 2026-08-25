package com.visionbank.approval.service;

import com.visionbank.approval.domain.AuditLog;
import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.repository.AuditLogRepository;
import com.visionbank.approval.repository.OutboxEventRepository;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ExpiryTransitionService {

    private final ApprovalRequestRepository requests;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;

    public ExpiryTransitionService(ApprovalRequestRepository requests, AuditLogRepository audits, OutboxEventRepository outbox) {
        this.requests = requests;
        this.audits = audits;
        this.outbox = outbox;
    }

    // Each candidate goes through the SAME guarded update as every other transition —
    // never a bulk UPDATE — so an in-flight approve() and this sweep can't both "win".
    // workflow/currentState are resolved by the caller (ExpirySweeper), which already knows
    // the candidate's own bound workflow from its workflow_id column.
    @Transactional
    public boolean expireOne(String requestId, long expectedVersion, WorkflowDefinition workflow, String currentState) {
        Transition transition = workflow.transitionsFrom(currentState).stream()
                .filter(t -> t.name().equals("expire"))
                .findFirst()
                .orElse(null);
        if (transition == null) {
            return false; // this stage doesn't offer expiry — not every workflow's every stage has to
        }
        int rows = requests.guardedTransition(requestId, currentState, expectedVersion, transition.to());
        if (rows == 0) {
            return false; // lost the race to a concurrent approve/reject/cancel — not an error
        }
        AuditLog log = new AuditLog();
        log.setRequestId(requestId);
        log.setAction("EXPIRED");
        log.setPreviousState(currentState);
        log.setNewState(transition.to());
        log.setCreatedAt(Instant.now());
        audits.save(log);

        for (String eventType : workflow.eventsFor(transition.to())) {
            OutboxEvent event = new OutboxEvent();
            event.setRequestId(requestId);
            event.setEventType(eventType);
            event.setEventVersion(1);
            event.setPayload("{\"requestId\":\"" + requestId + "\"}");
            event.setCreatedAt(Instant.now());
            outbox.save(event);
        }
        return true;
    }
}
