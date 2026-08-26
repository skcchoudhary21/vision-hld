package com.visionbank.banking.ui;

import java.time.Instant;
import java.util.List;

public record ApprovalSummaryDto(
        String requestId, String makerId, String workflowId, int workflowVersion,
        String currentState, String currentStageLabel, boolean terminal,
        Integer requiredApprovals, Integer currentApprovals,
        List<String> eligibleRoles, Instant createdAt) {}
