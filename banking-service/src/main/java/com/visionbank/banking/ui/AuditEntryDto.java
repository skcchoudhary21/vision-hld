package com.visionbank.banking.ui;

public record AuditEntryDto(String action, String previousState, String newState,
                             String actorId, String actorRole, String createdAt) {}
