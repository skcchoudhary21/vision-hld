package com.visionbank.banking.domain;

public enum TransferState {
    // CREATED is internal-only: the row exists but isn't yet linked to an
    // approval workflow (approvalRequestId still null) -- the narrow gap
    // between persistCreated() and markPendingApproval()'s separate
    // transactions, needed so ApprovalEventListener can tell "not ready yet"
    // apart from "ready" if a webhook wins that race. Never shown to a user.
    CREATED, PENDING_APPROVAL, RELEASE_PENDING, RELEASED, REJECTED, CANCELLED, EXPIRED, FAILED
}
