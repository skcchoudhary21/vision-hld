package com.visionbank.approval.web;

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

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;

    @Test
    void listIncludesEveryFixtureWorkflow() throws Exception {
        mockMvc.perform(get("/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.workflowId=='transfer-auto-release')]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='transfer-single-checker')]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='transfer-high-value')]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='privileged-access' && @.version==1)]").exists())
                .andExpect(jsonPath("$[?(@.workflowId=='privileged-access' && @.version==2)]").exists());
    }

    @Test
    void detailDistinguishesPrivilegedAccessVersionsByRequiredApprovals() throws Exception {
        mockMvc.perform(get("/workflows/privileged-access/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitions[?(@.name=='approve' && @.from=='SECURITY_REVIEW')].requiredApprovals",
                        is(List.of(1))));

        mockMvc.perform(get("/workflows/privileged-access/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitions[?(@.name=='approve' && @.from=='SECURITY_REVIEW')].requiredApprovals",
                        is(List.of(2))));
    }

    @Test
    void unknownWorkflowReturns404() throws Exception {
        mockMvc.perform(get("/workflows/does-not-exist/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownVersionOfKnownWorkflowReturns404() throws Exception {
        mockMvc.perform(get("/workflows/privileged-access/99"))
                .andExpect(status().isNotFound());
    }
}
