package com.visionbank.banking.approval;

public class TransferNotYetVisibleException extends RuntimeException {
    public TransferNotYetVisibleException(String transferId) {
        super("No transfer visible yet for id " + transferId + " — retry expected");
    }
}
