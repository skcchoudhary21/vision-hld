package com.visionbank.approval.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

// Approval policy used to be two hardcoded thresholds in banking-service; now
// it's an editable table owned here (approval-engine already owns the
// workflow catalog, so it owns which workflow a policy routes to as well).
// On a fresh database this seeds the same three tiers the hardcoded version
// used, so existing behavior is unchanged until someone edits it.
@Component
public class PolicyRuleSeeder implements ApplicationRunner {

    private final PolicyRuleRepository rules;
    private final long autoReleaseCeiling;
    private final long singleCheckerCeiling;

    public PolicyRuleSeeder(PolicyRuleRepository rules,
                             @Value("${policy.auto-release-ceiling-minor-units}") long autoReleaseCeiling,
                             @Value("${policy.single-checker-ceiling-minor-units}") long singleCheckerCeiling) {
        this.rules = rules;
        this.autoReleaseCeiling = autoReleaseCeiling;
        this.singleCheckerCeiling = singleCheckerCeiling;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (rules.count() > 0) return;
        rules.saveAll(List.of(
                new PolicyRule(null, 0L, autoReleaseCeiling - 1, "transfer-auto-release", 1),
                new PolicyRule(null, autoReleaseCeiling, singleCheckerCeiling - 1, "transfer-single-checker", 1),
                new PolicyRule(null, singleCheckerCeiling, null, "transfer-high-value", 1)));
    }
}
