package com.visionbank.transfer.web.dto;

import com.visionbank.transfer.domain.TransferState;

public record TransferResponseDto(String transferId, TransferState state) {}
