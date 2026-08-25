package com.visionbank.banking.approval;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.banking.policy.ApprovalPolicy;
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
}
