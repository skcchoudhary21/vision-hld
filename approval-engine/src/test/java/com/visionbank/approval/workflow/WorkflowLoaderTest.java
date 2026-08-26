package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLoaderTest {

    @Test
    void loadsDefinitionFromClasspathYaml() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-approval.yaml");

        assertThat(def.name()).isEqualTo("transfer-approval");
        assertThat(def.initialState()).isEqualTo("SUBMITTED");
        assertThat(def.states()).extracting(WorkflowDefinition.StateDef::id).containsExactlyInAnyOrder(
                "SUBMITTED", "PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
        assertThat(def.terminalStates()).containsExactlyInAnyOrder("APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
    }

    @Test
    void transitionsFromSubmittedIncludeAutoApproveAndRequireApproval() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-approval.yaml");

        assertThat(def.transitionsFrom("SUBMITTED"))
                .extracting(Transition::name)
                .containsExactlyInAnyOrder("auto_approve", "require_approval");
    }

    @Test
    void approveTransitionGuardIsApprovalsSatisfied() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-approval.yaml");

        Transition approve = def.transitionsFrom("PENDING_APPROVAL").stream()
                .filter(t -> t.name().equals("approve"))
                .findFirst()
                .orElseThrow();

        assertThat(approve.guard()).isEqualTo("approvals_satisfied");
        assertThat(approve.to()).isEqualTo("APPROVED");
    }

    @Test
    void eventsFiresOnlyOnTerminalStates() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-approval.yaml");

        assertThat(def.eventsFor("APPROVED")).containsExactly("ApprovalApproved");
        assertThat(def.eventsFor("PENDING_APPROVAL")).isEmpty();
    }

    @Test
    void loadingDefinitionWithDuplicateTransitionIdentityFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-duplicate-transition.yaml"));
    }
}
