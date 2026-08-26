package com.visionbank.banking.web.dto;

import com.visionbank.banking.domain.TransferState;

import java.time.Instant;

public record TransferDetailDto(
        String transferId, String makerId, String fromAccount, String toAccount,
        long amountMinorUnits, String currency, TransferState state,
        String approvalRequestId, Instant createdAt) {}
