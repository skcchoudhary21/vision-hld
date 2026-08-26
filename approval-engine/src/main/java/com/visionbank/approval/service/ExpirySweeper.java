package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.workflow.WorkflowDefinition;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ExpirySweeper {

    private final ApprovalRequestRepository requests;
    private final ExpiryTransitionService transitionService;
    private final WorkflowRegistry workflowRegistry;

    public ExpirySweeper(ApprovalRequestRepository requests, ExpiryTransitionService transitionService, WorkflowRegistry workflowRegistry) {
        this.requests = requests;
        this.transitionService = transitionService;
        this.workflowRegistry = workflowRegistry;
    }

    @Scheduled(fixedDelay = 60000)
    public int sweepOnce() {
        List<String> nonTerminalStates = workflowRegistry.allNonTerminalStates();
        List<ApprovalRequest> candidates = requests.findByStateInAndExpiresAtBefore(nonTerminalStates, Instant.now());
        int expiredCount = 0;
        for (ApprovalRequest candidate : candidates) {
            WorkflowDefinition workflow = candidate.getPolicySnapshot().workflow();
            if (transitionService.expireOne(candidate.getRequestId(), candidate.getVersion(), workflow, candidate.getState())) {
                expiredCount++;
            }
        }
        return expiredCount;
    }

    // Thin delegator so existing/prior test call sites (sweeper.expireOne(requestId, version))
    // still exercise the real transactional bean rather than a self-invoked no-op.
    public boolean expireOne(String requestId, long expectedVersion) {
        ApprovalRequest request = requests.findByRequestId(requestId)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(requestId));
        WorkflowDefinition workflow = request.getPolicySnapshot().workflow();
        return transitionService.expireOne(requestId, expectedVersion, workflow, request.getState());
    }
}
