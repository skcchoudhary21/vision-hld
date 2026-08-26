package com.visionbank.banking.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyResolverTest {

    private final PolicyResolver resolver = new PolicyResolver(500000L, 5000000L);

    @Test
    void belowAutoReleaseCeilingSelectsAutoReleaseWorkflow() {
        WorkflowSelection selection = resolver.resolve(100000L);
        assertThat(selection.workflowId()).isEqualTo("transfer-auto-release");
        assertThat(selection.workflowVersion()).isEqualTo(1);
    }

    @Test
    void betweenCeilingsSelectsSingleCheckerWorkflow() {
        WorkflowSelection selection = resolver.resolve(1000000L);
        assertThat(selection.workflowId()).isEqualTo("transfer-single-checker");
    }

    @Test
    void atOrAboveSingleCheckerCeilingSelectsHighValueWorkflow() {
        WorkflowSelection selection = resolver.resolve(5000000L);
        assertThat(selection.workflowId()).isEqualTo("transfer-high-value");
    }
}
