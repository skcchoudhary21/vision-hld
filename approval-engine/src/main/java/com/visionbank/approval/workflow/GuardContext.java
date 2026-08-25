package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.PolicySnapshot;

public record GuardContext(
        String makerId,
        PolicySnapshot policy,
        long currentApprovalCount,
        String actorId,
        String actorRole,
        boolean slaExpired) {}
