package com.visionbank.approval.policy;

public record PolicyRuleDto(
        Long id, long minAmountMinorUnits, Long maxAmountMinorUnits,
        String workflowId, int workflowVersion) {}
