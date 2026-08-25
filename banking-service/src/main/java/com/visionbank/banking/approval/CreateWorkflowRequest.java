package com.visionbank.banking.approval;

import com.visionbank.banking.policy.ApprovalPolicy;

import java.time.Instant;

public record CreateWorkflowRequest(
        String requestId, String requestType, String makerId,
        ApprovalPolicy policy, String payloadJson, Instant expiresAt) {}
