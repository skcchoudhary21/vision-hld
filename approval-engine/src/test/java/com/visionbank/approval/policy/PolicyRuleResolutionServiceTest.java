package com.visionbank.approval.policy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PolicyRuleResolutionServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired PolicyRuleResolutionService service;

    @Test
    void resolvesAutoReleaseTierForASmallAmount() {
        PolicyResolutionDto resolution = service.resolve(100_00L);

        assertThat(resolution.workflowId()).isEqualTo("transfer-auto-release");
        assertThat(resolution.workflowVersion()).isEqualTo(1);
    }

    @Test
    void throwsWhenNoRuleCoversTheAmount() {
        assertThatThrownBy(() -> service.resolve(-1L)).isInstanceOf(PolicyRuleNotFoundException.class);
    }
}
