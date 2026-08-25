package com.visionbank.transfer.service;

public record SubmitTransferCommand(String makerId, String fromAccount, String toAccount, long amountMinorUnits, String currency) {}
