package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public record CreateApprovalRequestDto(
        @NotBlank String requestId,
        @NotBlank String requestType,
        @NotBlank String makerId,
        @NotNull Map<String, StagePolicyDto> stagePolicies,
        boolean makerCanApprove,
        @NotBlank String payloadJson,
        @NotNull Instant expiresAt) {}
