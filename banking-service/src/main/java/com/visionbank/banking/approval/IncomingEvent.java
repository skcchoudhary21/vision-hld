package com.visionbank.banking.approval;

public record IncomingEvent(String eventId, String eventType, String requestId) {}
