package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLoaderTest {

    @Test
    void loadsDefinitionFromClasspathYaml() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.name()).isEqualTo("transfer-approval");
        assertThat(def.initialState()).isEqualTo(ApprovalState.SUBMITTED);
        assertThat(def.states()).containsExactlyInAnyOrder(
                ApprovalState.SUBMITTED, ApprovalState.PENDING_APPROVAL, ApprovalState.APPROVED,
                ApprovalState.REJECTED, ApprovalState.CANCELLED, ApprovalState.EXPIRED);
    }

    @Test
    void transitionsFromSubmittedIncludeAutoApproveAndRequireApproval() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.transitionsFrom(ApprovalState.SUBMITTED))
                .extracting(Transition::name)
                .containsExactlyInAnyOrder("auto_approve", "require_approval");
    }

    @Test
    void approveTransitionGuardIsApprovalsSatisfied() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/transfer-approval.yaml");

        assertThat(def.byName("approve").guard()).isEqualTo("approvals_satisfied");
        assertThat(def.byName("approve").to()).isEqualTo(ApprovalState.APPROVED);
    }

    @Test
    void loadingDefinitionWithDuplicateTransitionNamesFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-duplicate-transition.yaml"));
    }
}
