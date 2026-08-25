package com.visionbank.transfer.service;

public class ValidationFailedException extends RuntimeException {
    public ValidationFailedException(String reason) {
        super(reason);
    }
}
