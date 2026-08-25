package com.visionbank.banking.approval;

public record WorkflowResponse(String requestId, String state, long version) {}
