package com.visionbank.banking.corebanking;

public interface CoreBankingClient {
    ValidationResult validate(String fromAccount, long amountMinorUnits, String duplicateKey);

    /**
     * Idempotent by transferId: a redelivered ApprovalApproved event, or a
     * retry after a lost response, must never move money twice for the same
     * transferId. This is a core-banking contract, not a Banking Service
     * concern — ReleaseService relies on it without re-implementing dedup.
     */
    boolean release(String transferId, String fromAccount, long amountMinorUnits);
}
