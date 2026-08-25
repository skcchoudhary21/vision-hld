package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowConfigTest {

    @Test
    void unknownGuardNameFailsAtWiringTimeNotAtFirstUse() {
        WorkflowConfig config = new WorkflowConfig();
        GuardRegistry emptyRegistry = new GuardRegistry(); // no guards registered

        assertThatThrownBy(() -> config.workflowDefinition("workflow/definitions/transfer-approval.yaml", emptyRegistry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No guard registered");
    }
}
