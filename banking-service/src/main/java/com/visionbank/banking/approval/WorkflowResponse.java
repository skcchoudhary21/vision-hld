package com.visionbank.transfer.approval;

public record WorkflowResponse(String requestId, String state, long version) {}
