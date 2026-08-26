package com.visionbank.banking.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PolicyResolver {

    private final long autoReleaseCeiling;
    private final long singleCheckerCeiling;

    public PolicyResolver(@Value("${policy.auto-release-ceiling-minor-units}") long autoReleaseCeiling,
                           @Value("${policy.single-checker-ceiling-minor-units}") long singleCheckerCeiling) {
        this.autoReleaseCeiling = autoReleaseCeiling;
        this.singleCheckerCeiling = singleCheckerCeiling;
    }

    public WorkflowSelection resolve(long amountMinorUnits) {
        if (amountMinorUnits < autoReleaseCeiling) {
            return new WorkflowSelection("transfer-auto-release", 1);
        }
        if (amountMinorUnits < singleCheckerCeiling) {
            return new WorkflowSelection("transfer-single-checker", 1);
        }
        return new WorkflowSelection("transfer-high-value", 1);
    }
}
