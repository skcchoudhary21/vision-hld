package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record CreateApprovalRequestDto(
        @NotBlank String requestId,
        @NotBlank String requestType,
        @NotBlank String makerId,
        @NotNull Integer requiredApprovals,
        @NotNull List<String> eligibleRoles,
        boolean makerCanApprove,
        @NotBlank String payloadJson,
        @NotNull Instant expiresAt) {}
