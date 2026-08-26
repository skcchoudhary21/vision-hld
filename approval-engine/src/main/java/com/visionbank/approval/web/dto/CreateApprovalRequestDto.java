package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateApprovalRequestDto(
        @NotBlank String requestId,
        @NotBlank String requestType,
        @NotBlank String makerId,
        @NotBlank String workflowId,
        @Min(1) int workflowVersion,
        @NotBlank String policyVersion,
        @NotBlank String payloadJson,
        @NotNull Instant expiresAt) {}
