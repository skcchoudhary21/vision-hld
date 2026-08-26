package com.visionbank.banking.ui;

import java.time.Instant;

public record ApprovalSummaryDto(
        String requestId, String workflowId, int workflowVersion,
        String currentState, String currentStageLabel, boolean terminal,
        Integer requiredApprovals, Integer currentApprovals,
        Instant createdAt) {}
