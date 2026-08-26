package com.visionbank.approval.web.dto;

import java.time.Instant;
import java.util.List;

public record ApprovalRequestSummaryDto(
        String requestId, String makerId, String workflowId, int workflowVersion,
        String currentState, String currentStageLabel, boolean terminal,
        Integer requiredApprovals, Integer currentApprovals,
        List<String> eligibleRoles, Instant createdAt) {}
