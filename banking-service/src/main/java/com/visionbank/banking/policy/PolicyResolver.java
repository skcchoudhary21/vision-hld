package com.visionbank.banking.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PolicyResolver {

    private final long autoReleaseCeiling;
    private final long singleCheckerCeiling;

    public PolicyResolver(@Value("${policy.auto-release-ceiling-minor-units}") long autoReleaseCeiling,
                           @Value("${policy.single-checker-ceiling-minor-units}") long singleCheckerCeiling) {
        this.autoReleaseCeiling = autoReleaseCeiling;
        this.singleCheckerCeiling = singleCheckerCeiling;
    }

    public ApprovalPolicy resolve(long amountMinorUnits) {
        if (amountMinorUnits < autoReleaseCeiling) {
            return new ApprovalPolicy(0, List.of(), false);
        }
        if (amountMinorUnits < singleCheckerCeiling) {
            return new ApprovalPolicy(1, List.of("TRANSFER_CHECKER"), false);
        }
        return new ApprovalPolicy(2, List.of("TRANSFER_CHECKER"), false);
    }
}
