package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.domain.AuditLog;
import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.repository.AuditLogRepository;
import com.visionbank.approval.repository.OutboxEventRepository;
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
    @Transactional
    public boolean expireOne(String requestId, long expectedVersion) {
        int rows = requests.guardedTransition(requestId, ApprovalState.PENDING_APPROVAL, expectedVersion, ApprovalState.EXPIRED);
        if (rows == 0) {
            return false; // lost the race to a concurrent approve/reject/cancel — not an error
        }
        AuditLog log = new AuditLog();
        log.setRequestId(requestId);
        log.setAction("EXPIRED");
        log.setPreviousState(ApprovalState.PENDING_APPROVAL);
        log.setNewState(ApprovalState.EXPIRED);
        log.setCreatedAt(Instant.now());
        audits.save(log);

        OutboxEvent event = new OutboxEvent();
        event.setRequestId(requestId);
        event.setEventType("ApprovalExpired");
        event.setEventVersion(1);
        event.setPayload("{\"requestId\":\"" + requestId + "\"}");
        event.setCreatedAt(Instant.now());
        outbox.save(event);
        return true;
    }
}
