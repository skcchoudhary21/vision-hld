package com.visionbank.approval.service;

public class ApprovalRequestNotFoundException extends RuntimeException {
    public ApprovalRequestNotFoundException(String requestId) {
        super("No approval request with id " + requestId);
    }
}
