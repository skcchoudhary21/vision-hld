package com.visionbank.transfer.web;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.transfer.web.dto.SubmitTransferDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer engineStub = new WireMockServer(9092);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("approval-engine.base-url", () -> "http://localhost:9092");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void startStub() {
        engineStub.start();
        // Fully qualified: MockMvcRequestBuilders.post (explicit import below)
        // shadows WireMock's post from the wildcard static import.
        engineStub.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"req-ctrl\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));
    }

    @AfterEach
    void stopStub() {
        engineStub.resetAll();
        engineStub.stop();
    }

    @Test
    void submitReturnsWaitingForApproval() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("WAITING_FOR_APPROVAL")));
    }

    @Test
    void submitWithInsufficientBalanceReturns422() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 999_999_999_00L, "AED");

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isUnprocessableEntity());
    }
}
