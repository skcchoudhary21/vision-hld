package com.visionbank.approval.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ActorCommandDto(@NotBlank String actorId, String actorRole) {}
