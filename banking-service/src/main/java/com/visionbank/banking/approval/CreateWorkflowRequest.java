package com.visionbank.transfer.approval;

import com.visionbank.transfer.policy.ApprovalPolicy;

import java.time.Instant;

public record CreateWorkflowRequest(
        String requestId, String requestType, String makerId,
        ApprovalPolicy policy, String payloadJson, Instant expiresAt) {}
