package com.visionbank.banking.approval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
public class ApprovalEngineClient {

    private final RestClient restClient;

    public ApprovalEngineClient(@Value("${approval-engine.base-url}") String baseUrl) {
        // Force HTTP/1.1: the JDK HttpClient's default HTTP/2 upgrade attempt against
        // an HTTP/1.1-only server (e.g. the Approval Engine, WireMock in tests) can fail
        // with "Received RST_STREAM: Stream cancelled".
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public WorkflowResponse createWorkflow(CreateWorkflowRequest req, String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "requestId", req.requestId(),
                "requestType", req.requestType(),
                "makerId", req.makerId(),
                "requiredApprovals", req.policy().requiredApprovals(),
                "eligibleRoles", req.policy().eligibleRoles(),
                "makerCanApprove", req.policy().makerCanApprove(),
                "payloadJson", req.payloadJson(),
                "expiresAt", req.expiresAt().toString());

        return restClient.post()
                .uri("/approvals")
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .body(WorkflowResponse.class);
    }
}
