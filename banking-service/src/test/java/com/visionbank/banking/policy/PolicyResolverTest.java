package com.visionbank.banking.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyResolverTest {

    private final PolicyResolver resolver = new PolicyResolver(500000L, 5000000L);

    @Test
    void belowAutoReleaseCeilingRequiresNoApprovals() {
        ApprovalPolicy policy = resolver.resolve(100000L);
        assertThat(policy.requiredApprovals()).isEqualTo(0);
    }

    @Test
    void betweenCeilingsRequiresOneApproval() {
        ApprovalPolicy policy = resolver.resolve(1000000L);
        assertThat(policy.requiredApprovals()).isEqualTo(1);
    }

    @Test
    void atOrAboveSingleCheckerCeilingRequiresTwoApprovals() {
        ApprovalPolicy policy = resolver.resolve(5000000L);
        assertThat(policy.requiredApprovals()).isEqualTo(2);
    }

    @Test
    void makerCanNeverApproveUnderThisPolicy() {
        assertThat(resolver.resolve(1000000L).makerCanApprove()).isFalse();
    }
}
