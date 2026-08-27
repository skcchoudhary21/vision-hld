package com.visionbank.approval.policy;

import org.springframework.stereotype.Service;

@Service
public class PolicyRuleResolutionService {

    private final PolicyRuleRepository rules;

    public PolicyRuleResolutionService(PolicyRuleRepository rules) {
        this.rules = rules;
    }

    public PolicyResolutionDto resolve(long amountMinorUnits) {
        return rules.findAllByOrderByMinAmountMinorUnitsAsc().stream()
                .filter(r -> r.covers(amountMinorUnits))
                .findFirst()
                .map(r -> new PolicyResolutionDto(r.getWorkflowId(), r.getWorkflowVersion()))
                .orElseThrow(() -> new PolicyRuleNotFoundException(amountMinorUnits));
    }
}
