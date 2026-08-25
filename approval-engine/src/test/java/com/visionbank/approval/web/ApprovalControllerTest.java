package com.visionbank.approval.web;

import tools.jackson.databind.ObjectMapper;
import com.visionbank.approval.web.dto.CreateApprovalRequestDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    private String createDto(String requestId, int required) throws Exception {
        return mapper.writeValueAsString(new CreateApprovalRequestDto(
                requestId, "TRANSFER_APPROVAL", "maker-1", required, List.of("TRANSFER_CHECKER"),
                false, "{}", Instant.now().plusSeconds(86400)));
    }

    @Test
    void createReturns200WithPendingApprovalState() throws Exception {
        mockMvc.perform(post("/approvals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(createDto("ctrl-1", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PENDING_APPROVAL")));
    }

    @Test
    void approveOnAlreadyApprovedRequestReturns409WithConcurrentStateChangeCode() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-2", 1)));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                .contentType("application/json")
                .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-1", "TRANSFER_CHECKER"))));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-2", "TRANSFER_CHECKER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_STATE_CHANGE")));
    }
}
