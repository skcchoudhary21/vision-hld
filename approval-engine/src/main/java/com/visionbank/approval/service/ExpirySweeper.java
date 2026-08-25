package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.ApprovalState;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ExpirySweeper {

    private final ApprovalRequestRepository requests;
    private final ExpiryTransitionService transitionService;

    public ExpirySweeper(ApprovalRequestRepository requests, ExpiryTransitionService transitionService) {
        this.requests = requests;
        this.transitionService = transitionService;
    }

    @Scheduled(fixedDelay = 60000)
    public int sweepOnce() {
        List<ApprovalRequest> candidates = requests.findByStateAndExpiresAtBefore(ApprovalState.PENDING_APPROVAL, Instant.now());
        int expiredCount = 0;
        for (ApprovalRequest candidate : candidates) {
            if (transitionService.expireOne(candidate.getRequestId(), candidate.getVersion())) {
                expiredCount++;
            }
        }
        return expiredCount;
    }

    // Thin delegator so existing/prior test call sites (sweeper.expireOne(...))
    // still exercise the real transactional bean rather than a self-invoked no-op.
    public boolean expireOne(String requestId, long expectedVersion) {
        return transitionService.expireOne(requestId, expectedVersion);
    }
}
