package com.visionbank.approval.service;

public class InvalidStateTransitionException extends RuntimeException {
    public final String requestId;
    public final String currentState;
    public final String requestedAction;

    public InvalidStateTransitionException(String requestId, String currentState, String requestedAction) {
        super("Action " + requestedAction + " is never valid from " + currentState + " (request " + requestId + ")");
        this.requestId = requestId;
        this.currentState = currentState;
        this.requestedAction = requestedAction;
    }
}
