package com.visionbank.approval.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency key already used with a different request body: " + key);
    }
}
