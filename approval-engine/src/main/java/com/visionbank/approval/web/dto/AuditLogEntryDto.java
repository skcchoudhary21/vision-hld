package com.visionbank.approval.web.dto;

import com.visionbank.approval.domain.ApprovalState;

import java.time.Instant;

public record AuditLogEntryDto(String action, ApprovalState previousState, ApprovalState newState,
                                String actorId, String actorRole, Instant createdAt) {}
