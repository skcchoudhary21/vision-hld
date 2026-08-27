package com.visionbank.banking.messaging;

import java.time.Instant;

public record CreateTransferApprovalCommand(String transferId, String makerId, long amountMinorUnits, Instant expiresAt) {}
