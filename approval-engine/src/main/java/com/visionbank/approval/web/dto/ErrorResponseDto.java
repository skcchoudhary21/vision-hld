package com.visionbank.approval.web.dto;

public record ErrorResponseDto(String code, String requestId, String currentState, String requestedAction) {}
