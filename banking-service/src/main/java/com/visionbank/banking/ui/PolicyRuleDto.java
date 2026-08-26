package com.visionbank.banking.ui;

public record PolicyRuleDto(
        Long id, long minAmountMinorUnits, Long maxAmountMinorUnits,
        String workflowId, int workflowVersion) {}
