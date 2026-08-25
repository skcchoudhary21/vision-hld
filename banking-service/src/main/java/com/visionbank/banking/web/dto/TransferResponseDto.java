package com.visionbank.banking.web.dto;

import com.visionbank.banking.domain.TransferState;

public record TransferResponseDto(String transferId, TransferState state) {}
