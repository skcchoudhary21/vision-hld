package com.visionbank.banking.service;

public class ValidationFailedException extends RuntimeException {
    public ValidationFailedException(String reason) {
        super(reason);
    }
}
