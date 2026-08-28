package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLoaderTest {

    @Test
    void loadsDefinitionFromClasspathYaml() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        assertThat(def.name()).isEqualTo("transfer-single-checker");
        assertThat(def.initialState()).isEqualTo("SUBMITTED");
        assertThat(def.states()).extracting(WorkflowDefinition.StateDef::id).containsExactlyInAnyOrder(
                "SUBMITTED", "PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
        assertThat(def.terminalStates()).containsExactlyInAnyOrder("APPROVED", "REJECTED", "CANCELLED", "EXPIRED");
    }

    @Test
    void transitionsFromSubmittedIncludeRequireApproval() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        assertThat(def.transitionsFrom("SUBMITTED"))
                .extracting(Transition::name)
                .containsExactly("require_approval");
    }

    @Test
    void approveTransitionCarriesGuardsRolesAndQuorum() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        Transition approve = def.transitionsFrom("PENDING_APPROVAL").stream()
                .filter(t -> t.name().equals("approve"))
                .findFirst()
                .orElseThrow();

        assertThat(approve.guards()).containsExactly("approvals_satisfied", "actor_is_not_maker");
        assertThat(approve.allowedRoles()).containsExactly("TRANSFER_CHECKER");
        assertThat(approve.requiredApprovals()).isEqualTo(1);
        assertThat(approve.to()).isEqualTo("APPROVED");
    }

    @Test
    void eventsFiresOnlyOnTerminalStates() {
        WorkflowDefinition def = new YamlWorkflowLoader().load("workflow/definitions/transfer-single-checker.yaml");

        assertThat(def.eventsFor("APPROVED")).containsExactly("ApprovalApproved");
        assertThat(def.eventsFor("PENDING_APPROVAL")).isEmpty();
    }

    @Test
    void loadingDefinitionWithDuplicateTransitionIdentityFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-duplicate-transition.yaml"));
    }

    @Test
    void requiredApprovalsBelowOneFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-zero-required-approvals.yaml"));
    }

    @Test
    void terminalStateNotDeclaredInStatesFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YamlWorkflowLoader().load("workflow/invalid-terminal-state-not-declared.yaml"));
    }
}
