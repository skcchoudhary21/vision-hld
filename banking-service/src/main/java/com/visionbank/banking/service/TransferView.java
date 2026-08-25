package com.visionbank.banking.service;

import com.visionbank.banking.domain.TransferState;

public record TransferView(String transferId, TransferState state) {}
