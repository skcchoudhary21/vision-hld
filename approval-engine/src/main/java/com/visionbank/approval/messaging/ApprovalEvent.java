package com.visionbank.approval.messaging;

public record ApprovalEvent(String eventId, String eventType, String requestId, String payload) {}
