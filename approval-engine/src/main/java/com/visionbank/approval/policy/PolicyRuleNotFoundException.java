package com.visionbank.approval.policy;

public class PolicyRuleNotFoundException extends RuntimeException {
    public PolicyRuleNotFoundException(long amountMinorUnits) {
        super("No approval policy rule covers amount " + amountMinorUnits);
    }
}
