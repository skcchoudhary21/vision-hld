package com.visionbank.transfer.corebanking;

public record ValidationResult(boolean sufficientBalance, boolean withinLimit, boolean duplicate) {
    public boolean isValid() {
        return sufficientBalance && withinLimit && !duplicate;
    }
}
