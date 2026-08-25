package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRegistryTest {

    @Test
    void loadsBothSampleWorkflows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThat(registry.get("transfer-approval").name()).isEqualTo("transfer-approval");
        assertThat(registry.get("privileged-access").name()).isEqualTo("privileged-access");
    }

    @Test
    void unknownWorkflowIdThrows() {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

        assertThatThrownBy(() -> registry.get("does-not-exist"))
                .isInstanceOf(IllegalStateException.class);
    }
}
