package com.visionbank.banking.web;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.banking.web.dto.SubmitTransferDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.is;
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
        // All amounts used below fall in the auto-release tier -- PolicyResolver
        // now resolves the workflow via approval-engine's own /policy-rules/resolve
        // rather than computing it locally.
        engineStub.stubFor(get(urlPathEqualTo("/policy-rules/resolve"))
                .willReturn(okJson("{\"workflowId\":\"transfer-auto-release\",\"workflowVersion\":1}")));
    }

    @AfterEach
    void stopStub() {
        engineStub.resetAll();
        engineStub.stop();
    }

    // Every request in this suite goes through actor(...) -- ActorHeaderInterceptor
    // requires X-Actor-Id/X-Actor-Role on all of /transfers/** and /ui-api/**.
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Actor-Id", "test-actor").header("X-Actor-Role", "MAKER");
    }

    @Test
    void submitReturnsPendingApproval() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");

        mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PENDING_APPROVAL")));
    }

    @Test
    void submitWithoutActorHeadersReturns400() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");

        mockMvc.perform(MockMvcRequestBuilders.post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitWithInsufficientBalanceReturns422() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 999_999_999_00L, "AED");

        mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getReturnsFullTransferDetails() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-detail", "ACC-FUNDED", "ACC-DEST", 1500_00L, "AED");
        String body = mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andReturn().getResponse().getContentAsString();
        String transferId = mapper.readTree(body).get("transferId").asString();

        mockMvc.perform(actor(MockMvcRequestBuilders.get("/transfers/" + transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId", is(transferId)))
                .andExpect(jsonPath("$.makerId", is("maker-detail")))
                .andExpect(jsonPath("$.fromAccount", is("ACC-FUNDED")))
                .andExpect(jsonPath("$.toAccount", is("ACC-DEST")))
                .andExpect(jsonPath("$.amountMinorUnits", is(1500_00)))
                .andExpect(jsonPath("$.currency", is("AED")))
                .andExpect(jsonPath("$.approvalRequestId").exists());
    }

    @Test
    void listFiltersTransfersByMakerId() throws Exception {
        mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(mapper.writeValueAsString(
                        new SubmitTransferDto("maker-a", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED"))));
        mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(mapper.writeValueAsString(
                        new SubmitTransferDto("maker-b", "ACC-FUNDED", "ACC-DEST", 2000_00L, "AED"))));

        mockMvc.perform(actor(MockMvcRequestBuilders.get("/transfers?makerId=maker-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].makerId", is(java.util.List.of("maker-a"))));
    }

    @Test
    void listByIdsBulkFetchesMultipleTransfersInOneCall() throws Exception {
        String body1 = mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(
                                new SubmitTransferDto("maker-bulk", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED"))))
                .andReturn().getResponse().getContentAsString();
        String body2 = mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(
                                new SubmitTransferDto("maker-bulk", "ACC-FUNDED", "ACC-DEST", 2000_00L, "AED"))))
                .andReturn().getResponse().getContentAsString();
        String id1 = mapper.readTree(body1).get("transferId").asString();
        String id2 = mapper.readTree(body2).get("transferId").asString();

        mockMvc.perform(actor(MockMvcRequestBuilders.get("/transfers?ids=" + id1 + "," + id2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[?(@.transferId=='" + id1 + "')]").exists())
                .andExpect(jsonPath("$[?(@.transferId=='" + id2 + "')]").exists());
    }
}
