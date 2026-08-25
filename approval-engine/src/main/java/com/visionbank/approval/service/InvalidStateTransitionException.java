package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;

public class InvalidStateTransitionException extends RuntimeException {
    public final String requestId;
    public final ApprovalState currentState;
    public final String requestedAction;

    public InvalidStateTransitionException(String requestId, ApprovalState currentState, String requestedAction) {
        super("Action " + requestedAction + " is never valid from " + currentState + " (request " + requestId + ")");
        this.requestId = requestId;
        this.currentState = currentState;
        this.requestedAction = requestedAction;
    }
}
