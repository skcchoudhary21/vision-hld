package com.visionbank.banking.approval;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.banking.policy.ApprovalPolicy;
import com.visionbank.banking.ui.WorkflowViewDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class ApprovalEngineClientTest {

    WireMockServer wireMock = new WireMockServer(9092);
    ApprovalEngineClient client;

    @BeforeEach
    void setUp() {
        wireMock.start();
        client = new ApprovalEngineClient("http://localhost:9092");
    }

    @AfterEach
    void tearDown() {
        wireMock.resetAll();
        wireMock.stop();
    }

    @Test
    void createWorkflowPostsToApprovalsAndParsesResponse() {
        wireMock.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"req-1\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

        CreateWorkflowRequest req = new CreateWorkflowRequest("req-1", "TRANSFER_APPROVAL", "maker-1",
                new ApprovalPolicy(1, List.of("TRANSFER_CHECKER"), false), "{}", Instant.now().plusSeconds(86400));

        WorkflowResponse response = client.createWorkflow(req, UUID.randomUUID().toString());

        assertThat(response.requestId()).isEqualTo("req-1");
        assertThat(response.state()).isEqualTo("PENDING_APPROVAL");
        wireMock.verify(postRequestedFor(urlEqualTo("/approvals"))
                .withHeader("Idempotency-Key", matching(".+"))
                .withRequestBody(matchingJsonPath("$.stagePolicies.PENDING_APPROVAL.requiredApprovals", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.stagePolicies.PENDING_APPROVAL.eligibleRoles[0]", equalTo("TRANSFER_CHECKER"))));
    }

    @Test
    void getWorkflowViewParsesGenericStagesShape() {
        // approval-engine's StageViewDto.approvals is really a list of
        // DecisionViewDto (actorId, actorRole, decision, createdAt) -- shaped
        // differently from banking-service's AuditEntryDto (action,
        // previousState, newState, actorId, actorRole, createdAt), which this
        // client reuses per the Task 8 brief rather than adding a fifth
        // near-duplicate DTO. This test locks in that the extra "decision"
        // field (absent from AuditEntryDto) and the missing action/previousState/
        // newState fields don't break deserialization.
        wireMock.stubFor(get(urlEqualTo("/approvals/req-1/workflow-view"))
                .willReturn(okJson("{"
                        + "\"workflowId\":\"privileged-access\",\"workflowVersion\":1,\"currentState\":\"MANAGER_APPROVAL\","
                        + "\"terminalStates\":[\"APPROVED\",\"REJECTED\",\"EXPIRED\"],"
                        + "\"stages\":["
                        + "{\"id\":\"SUBMITTED\",\"label\":\"Submitted\",\"status\":\"COMPLETED\",\"requiredApprovals\":null,\"completedApprovals\":null,\"approvals\":[]},"
                        + "{\"id\":\"SECURITY_REVIEW\",\"label\":\"Security Review\",\"status\":\"COMPLETED\",\"requiredApprovals\":1,\"completedApprovals\":1,"
                        + "\"approvals\":[{\"actorId\":\"sec-1\",\"actorRole\":\"SECURITY_REVIEWER\",\"decision\":\"APPROVE\",\"createdAt\":\"2026-08-26T10:00:00Z\"}]},"
                        + "{\"id\":\"MANAGER_APPROVAL\",\"label\":\"Manager Approval\",\"status\":\"IN_PROGRESS\",\"requiredApprovals\":1,\"completedApprovals\":0,\"approvals\":[]}"
                        + "]}")));

        WorkflowViewDto view = client.getWorkflowView("req-1");

        assertThat(view.workflowId()).isEqualTo("privileged-access");
        assertThat(view.workflowVersion()).isEqualTo(1);
        assertThat(view.currentState()).isEqualTo("MANAGER_APPROVAL");
        assertThat(view.terminalStates()).containsExactly("APPROVED", "REJECTED", "EXPIRED");
        assertThat(view.stages()).hasSize(3);
        assertThat(view.stages().get(1).id()).isEqualTo("SECURITY_REVIEW");
        assertThat(view.stages().get(1).status()).isEqualTo("COMPLETED");
        assertThat(view.stages().get(1).approvals()).hasSize(1);
        assertThat(view.stages().get(1).approvals().get(0).actorId()).isEqualTo("sec-1");
        assertThat(view.stages().get(1).approvals().get(0).actorRole()).isEqualTo("SECURITY_REVIEWER");
        assertThat(view.stages().get(2).status()).isEqualTo("IN_PROGRESS");
    }
}
