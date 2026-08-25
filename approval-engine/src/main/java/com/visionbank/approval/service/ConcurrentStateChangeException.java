package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;

public class ConcurrentStateChangeException extends RuntimeException {
    public final String requestId;
    public final ApprovalState currentState;

    public ConcurrentStateChangeException(String requestId, ApprovalState currentState) {
        super("Request " + requestId + " already moved to " + currentState);
        this.requestId = requestId;
        this.currentState = currentState;
    }
}
