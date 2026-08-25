package com.visionbank.transfer.domain;

public enum TransferState {
    CREATED, VALIDATED, WAITING_FOR_APPROVAL, RELEASE_PENDING, RELEASED, REJECTED, CANCELLED, EXPIRED
}
