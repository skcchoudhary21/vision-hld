package com.visionbank.banking.approval;

import com.visionbank.banking.policy.WorkflowSelection;

import java.time.Instant;

public record CreateWorkflowRequest(
        String requestId, String requestType, String makerId,
        WorkflowSelection workflow, String payloadJson, Instant expiresAt) {}
