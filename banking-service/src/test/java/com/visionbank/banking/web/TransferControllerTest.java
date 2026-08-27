package com.visionbank.banking.web;

import tools.jackson.databind.ObjectMapper;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.service.SubmitTransferCommand;
import com.visionbank.banking.service.TransferPersistenceService;
import com.visionbank.banking.web.dto.SubmitTransferDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired TransferPersistenceService persistenceService;

    // Every request in this suite goes through actor(...) -- ActorHeaderInterceptor
    // requires X-Actor-Id/X-Actor-Role on all of /transfers/** and /ui-api/**.
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Actor-Id", "test-actor").header("X-Actor-Role", "MAKER");
    }

    @Test
    void submitReturnsCreated() throws Exception {
        SubmitTransferDto dto = new SubmitTransferDto("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");

        mockMvc.perform(actor(MockMvcRequestBuilders.post("/transfers"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CREATED")));
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
        // Seeded directly rather than via POST /transfers -- this test is about the
        // GET response shape once a workflow is linked, not about the async creation
        // pipeline (Task 9 covers that, in approval-engine's own test suite, since it's
        // approval-engine's consumer that performs the link). Same fixture-seeding
        // pattern TransferSubmissionServiceTest already uses for its resume-path test.
        SubmitTransferCommand cmd = new SubmitTransferCommand("maker-detail", "ACC-FUNDED", "ACC-DEST", 1500_00L, "AED");
        Transfer transfer = persistenceService.persistCreated("t-detail-1", cmd, UUID.randomUUID().toString(), Instant.now().plusSeconds(300));
        persistenceService.markPendingApproval(transfer.getTransferId(), "t-detail-1");

        mockMvc.perform(actor(MockMvcRequestBuilders.get("/transfers/" + transfer.getTransferId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId", is(transfer.getTransferId())))
                .andExpect(jsonPath("$.makerId", is("maker-detail")))
                .andExpect(jsonPath("$.fromAccount", is("ACC-FUNDED")))
                .andExpect(jsonPath("$.toAccount", is("ACC-DEST")))
                .andExpect(jsonPath("$.amountMinorUnits", is(1500_00)))
                .andExpect(jsonPath("$.currency", is("AED")))
                .andExpect(jsonPath("$.approvalRequestId", is("t-detail-1")));
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
