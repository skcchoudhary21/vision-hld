package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRegistryTest {

    @Test
    void loadsAllSampleWorkflowsIncludingMultipleVersionsOfTheSameId() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThat(registry.get("transfer-auto-release", 1).name()).isEqualTo("transfer-auto-release");
        assertThat(registry.get("transfer-single-checker", 1).name()).isEqualTo("transfer-single-checker");
        assertThat(registry.get("transfer-high-value", 1).name()).isEqualTo("transfer-high-value");
        assertThat(registry.get("privileged-access", 1).version()).isEqualTo(1);
        assertThat(registry.get("privileged-access", 2).version()).isEqualTo(2);
    }

    @Test
    void unknownWorkflowIdThrows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThatThrownBy(() -> registry.get("does-not-exist", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void knownWorkflowIdWithUnknownVersionThrows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThatThrownBy(() -> registry.get("privileged-access", 99))
                .isInstanceOf(IllegalStateException.class);
    }
}
