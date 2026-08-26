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

    private String createDto(String requestId, String workflowId) throws Exception {
        return mapper.writeValueAsString(new CreateApprovalRequestDto(
                requestId, "TRANSFER_APPROVAL", "maker-1", workflowId, 1, "v1", "{}", Instant.now().plusSeconds(86400)));
    }

    @Test
    void createReturns200WithPendingApprovalState() throws Exception {
        mockMvc.perform(post("/approvals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(createDto("ctrl-1", "transfer-single-checker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PENDING_APPROVAL")));
    }

    @Test
    void approveOnAlreadyApprovedRequestReturns409WithConcurrentStateChangeCode() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-2", "transfer-single-checker")));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                .contentType("application/json")
                .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-1", "TRANSFER_CHECKER"))));

        mockMvc.perform(post("/approvals/ctrl-2/approve")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-2", "TRANSFER_CHECKER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_STATE_CHANGE")));
    }

    @Test
    void workflowViewShowsStageProgressForAPendingRequest() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-4", "transfer-high-value")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/approvals/ctrl-4/workflow-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState", is("PENDING_APPROVAL")))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].status", is(List.of("IN_PROGRESS"))))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].requiredApprovals", is(List.of(2))))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].completedApprovals", is(List.of(0))))
                .andExpect(jsonPath("$.stages[?(@.id=='PENDING_APPROVAL')].approvals[0]").doesNotExist())
                .andExpect(jsonPath("$.stages[?(@.id=='SUBMITTED')].status", is(List.of("COMPLETED"))))
                .andExpect(jsonPath("$.availableActions[?(@.name=='approve')].allowedRoles", is(List.of(List.of("TRANSFER_CHECKER")))))
                .andExpect(jsonPath("$.availableActions[?(@.name=='cancel')].name", is(List.of("cancel"))));
    }

    @Test
    void workflowViewShowsFailedStatusForARejectedRequest() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-5", "transfer-single-checker")));

        mockMvc.perform(post("/approvals/ctrl-5/reject")
                .contentType("application/json")
                .content(mapper.writeValueAsString(new com.visionbank.approval.web.dto.ActorCommandDto("checker-1", "TRANSFER_CHECKER"))));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/approvals/ctrl-5/workflow-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState", is("REJECTED")))
                .andExpect(jsonPath("$.stages[?(@.id=='REJECTED')].status", is(List.of("FAILED"))))
                .andExpect(jsonPath("$.stages[?(@.id=='APPROVED')].status", is(List.of("PENDING"))));
    }

    @Test
    void getReturnsCurrentStateAndReturns404WhenNotFound() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("ctrl-3", "transfer-single-checker")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals/ctrl-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PENDING_APPROVAL")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsAllCreatedRequestsNewestFirst() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("list-1", "transfer-single-checker")));
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("list-2", "transfer-auto-release")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId=='list-1')].currentState", is(List.of("PENDING_APPROVAL"))))
                .andExpect(jsonPath("$[?(@.requestId=='list-2')].currentState", is(List.of("APPROVED"))))
                .andExpect(jsonPath("$[?(@.requestId=='list-2')].terminal", is(List.of(true))))
                .andExpect(jsonPath("$[?(@.requestId=='list-1')].terminal", is(List.of(false))));
    }

    @Test
    void statusFilterSeparatesPendingFromCompleted() throws Exception {
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("filter-pending-1", "transfer-single-checker")));
        mockMvc.perform(post("/approvals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(createDto("filter-done-1", "transfer-auto-release")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals?status=pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId=='filter-pending-1')]").exists())
                .andExpect(jsonPath("$[?(@.requestId=='filter-done-1')]").doesNotExist());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/approvals?status=completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId=='filter-done-1')]").exists())
                .andExpect(jsonPath("$[?(@.requestId=='filter-pending-1')]").doesNotExist());
    }
}
