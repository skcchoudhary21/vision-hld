package com.visionbank.banking.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record SubmitTransferDto(
        @NotBlank String makerId,
        @NotBlank String fromAccount,
        @NotBlank String toAccount,
        @Positive long amountMinorUnits,
        @NotBlank String currency) {}
