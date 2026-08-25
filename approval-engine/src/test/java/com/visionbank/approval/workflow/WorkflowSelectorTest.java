package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowSelectorTest {

    private final WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());

    @Test
    void resolvesTransferApprovalRequestType() {
        WorkflowSelector selector = new WorkflowSelector("workflow/workflow-selection.yaml", registry);

        assertThat(selector.resolve("TRANSFER_APPROVAL").name()).isEqualTo("transfer-approval");
    }

    @Test
    void resolvesPrivilegedAccessRequestType() {
        WorkflowSelector selector = new WorkflowSelector("workflow/workflow-selection.yaml", registry);

        assertThat(selector.resolve("PRIVILEGED_ACCESS").name()).isEqualTo("privileged-access");
    }

    @Test
    void unknownRequestTypeThrows() {
        WorkflowSelector selector = new WorkflowSelector("workflow/workflow-selection.yaml", registry);

        assertThatThrownBy(() -> selector.resolve("SOMETHING_ELSE"))
                .isInstanceOf(IllegalStateException.class);
    }
}
