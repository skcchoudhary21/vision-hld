package com.visionbank.approval.web.dto;

import java.time.Instant;

public record DecisionViewDto(String actorId, String actorRole, String decision, Instant createdAt) {}
