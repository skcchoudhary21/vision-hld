package com.visionbank.approval.workflow;

public record GuardContext(
        String makerId,
        long currentApprovalCount,
        String actorId,
        String actorRole,
        boolean slaExpired,
        String currentState,
        Integer requiredApprovals) {}
