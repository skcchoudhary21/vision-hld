package com.visionbank.approval.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.approval.domain.OutboxEvent;
import com.visionbank.approval.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class OutboxRelayTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer wireMock = new WireMockServer(9091);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("banking-service.webhook-url", () -> "http://localhost:9091/internal/events");
    }

    @Autowired OutboxRelay relay;
    @Autowired OutboxEventRepository outbox;

    @BeforeEach
    void startWireMock() {
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.resetAll();
        wireMock.stop();
    }

    private OutboxEvent unpublishedEvent(String requestId) {
        OutboxEvent event = new OutboxEvent();
        event.setRequestId(requestId);
        event.setEventType("ApprovalApproved");
        event.setEventVersion(1);
        event.setPayload("{\"requestId\":\"" + requestId + "\"}");
        event.setCreatedAt(Instant.now());
        return outbox.save(event);
    }

    @Test
    void publishesUnpublishedEventAndMarksItPublished() {
        wireMock.stubFor(post(urlEqualTo("/internal/events")).willReturn(ok()));
        OutboxEvent event = unpublishedEvent("relay-1");

        int published = relay.relayOnce();

        assertThat(published).isGreaterThanOrEqualTo(1);
        // Content-Type must be application/json: the payload is a raw JSON string, and
        // RestClient's default StringHttpMessageConverter sends text/plain unless told
        // otherwise, which the real receiving controller (@RequestBody DTO) rejects with 415.
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/events"))
                .withHeader("Content-Type", equalTo("application/json")));
        assertThat(outbox.findById(event.getEventId()).get().getPublishedAt()).isNotNull();
    }

    @Test
    void leavesEventUnpublishedWhenBankingServiceIsDown() {
        wireMock.stubFor(post(urlEqualTo("/internal/events")).willReturn(serverError()));
        OutboxEvent event = unpublishedEvent("relay-2");

        relay.relayOnce();

        assertThat(outbox.findById(event.getEventId()).get().getPublishedAt()).isNull();
    }
}
