package com.visionbank.approval.web.dto;

import java.time.Instant;

public record AuditLogEntryDto(String action, String previousState, String newState,
                                String actorId, String actorRole, Instant createdAt) {}
