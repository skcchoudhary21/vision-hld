package com.visionbank.approval.service;

public class ConcurrentStateChangeException extends RuntimeException {
    public final String requestId;
    public final String currentState;

    public ConcurrentStateChangeException(String requestId, String currentState) {
        super("Request " + requestId + " already moved to " + currentState);
        this.requestId = requestId;
        this.currentState = currentState;
    }
}
