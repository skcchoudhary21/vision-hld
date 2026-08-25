package com.visionbank.approval.service;

import com.visionbank.approval.domain.PolicySnapshot;

import java.time.Instant;

public record CreateApprovalRequest(
        String requestId,
        String requestType,
        String makerId,
        PolicySnapshot policy,
        String payloadJson,
        Instant expiresAt) {}
