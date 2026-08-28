package com.visionbank.approval.policy;

import tools.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PolicyRuleControllerTest {

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

    // One sequential test, not several: PUT replaces the whole table and
    // there's no per-row identity to scope assertions by, so splitting into
    // separate @Test methods would make "seeded defaults" depend on JUnit's
    // unspecified method execution order (see banking-service's identical rule
    // for why this shape was chosen).
    @Test
    void listReturnsSeededDefaultsThenPutReplacesThenResolveUsesTheNewRules() throws Exception {
        mockMvc.perform(get("/policy-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowId", is("transfer-auto-release")))
                .andExpect(jsonPath("$[0].minAmountMinorUnits", is(0)))
                .andExpect(jsonPath("$[2].workflowId", is("transfer-high-value")))
                .andExpect(jsonPath("$[3].workflowId", is("privileged-access")))
                .andExpect(jsonPath("$[3].workflowVersion", is(2)))
                .andExpect(jsonPath("$[3].maxAmountMinorUnits").doesNotExist());

        mockMvc.perform(get("/policy-rules/resolve?amountMinorUnits=1000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId", is("transfer-single-checker")))
                .andExpect(jsonPath("$.workflowVersion", is(1)));

        String body = mapper.writeValueAsString(List.of(
                new PolicyRuleDto(null, 0L, 999_999L, "transfer-single-checker", 1),
                new PolicyRuleDto(null, 1_000_000L, null, "transfer-high-value", 1)));

        mockMvc.perform(put("/policy-rules").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)));

        mockMvc.perform(get("/policy-rules/resolve?amountMinorUnits=1000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId", is("transfer-high-value")));

        mockMvc.perform(get("/policy-rules/resolve?amountMinorUnits=999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId", is("transfer-high-value")));

        // Same reason this is appended rather than a separate @Test: it needs
        // a narrow rule set that leaves gaps, which would otherwise stomp on
        // whichever test happens to run after it.
        String narrowBody = mapper.writeValueAsString(List.of(
                new PolicyRuleDto(null, 1000L, 2000L, "transfer-single-checker", 1)));
        mockMvc.perform(put("/policy-rules").contentType("application/json").content(narrowBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/policy-rules/resolve?amountMinorUnits=1"))
                .andExpect(status().isNotFound());

        // Appended for the same reason as above: this must run without disturbing table
        // state other tests depend on, so it has to be the rejecting case -- a rejected
        // PUT must not touch the table at all.
        String overlappingBody = mapper.writeValueAsString(List.of(
                new PolicyRuleDto(null, 0L, 100_000L, "transfer-single-checker", 1),
                new PolicyRuleDto(null, 50_000L, 200_000L, "transfer-high-value", 1)));
        mockMvc.perform(put("/policy-rules").contentType("application/json").content(overlappingBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));

        // The rejected PUT above must not have replaced the table.
        mockMvc.perform(get("/policy-rules/resolve?amountMinorUnits=1500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId", is("transfer-single-checker")));
    }
}
