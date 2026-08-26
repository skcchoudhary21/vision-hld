package com.visionbank.approval.service;

import java.time.Instant;

public record CreateApprovalRequest(
        String requestId,
        String requestType,
        String makerId,
        String workflowId,
        int workflowVersion,
        String policyVersion,
        String payloadJson,
        Instant expiresAt) {}
