package com.visionbank.approval.web.dto;

import java.time.Instant;

public record ApprovalRequestSummaryDto(
        String requestId, String workflowId, int workflowVersion,
        String currentState, String currentStageLabel, boolean terminal,
        Integer requiredApprovals, Integer currentApprovals,
        Instant createdAt) {}
