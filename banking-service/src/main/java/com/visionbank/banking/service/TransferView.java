package com.visionbank.transfer.service;

import com.visionbank.transfer.domain.TransferState;

public record TransferView(String transferId, TransferState state) {}
