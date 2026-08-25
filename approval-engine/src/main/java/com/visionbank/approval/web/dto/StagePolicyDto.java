package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record StagePolicyDto(@NotNull Integer requiredApprovals, @NotNull List<String> eligibleRoles) {}
