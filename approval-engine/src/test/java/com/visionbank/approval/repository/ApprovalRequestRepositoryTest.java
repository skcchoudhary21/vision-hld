package com.visionbank.approval.repository;

import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.PolicySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class ApprovalRequestRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // stringtype=unspecified: several columns are jsonb fed by a plain
        // Java String; without this the Postgres JDBC driver binds the
        // String as varchar and Postgres rejects the insert/update. See the
        // matching comment in application.yml.
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ApprovalRequestRepository repository;

    private ApprovalRequest newRequest(String id) {
        ApprovalRequest r = new ApprovalRequest();
        r.setRequestId(id);
        r.setRequestType("TRANSFER_APPROVAL");
        r.setState("PENDING_APPROVAL");
        r.setVersion(0L);
        r.setMakerId("maker-1");
        r.setPolicySnapshot(new PolicySnapshot("v1", 2, List.of("TRANSFER_CHECKER"), false));
        r.setPayload("{}");
        r.setCreatedAt(Instant.now());
        r.setExpiresAt(Instant.now().plusSeconds(86400));
        return r;
    }

    @Test
    void guardedTransitionSucceedsWhenStateAndVersionMatch() {
        repository.saveAndFlush(newRequest("req-1"));

        int rows = repository.guardedTransition("req-1", "PENDING_APPROVAL", 0L, "APPROVED");

        assertThat(rows).isEqualTo(1);
        Optional<ApprovalRequest> reloaded = repository.findByRequestId("req-1");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getState()).isEqualTo("APPROVED");
        assertThat(reloaded.get().getVersion()).isEqualTo(1L);
    }

    @Test
    void guardedTransitionFailsWhenVersionStale() {
        repository.saveAndFlush(newRequest("req-2"));

        int rows = repository.guardedTransition("req-2", "PENDING_APPROVAL", 5L, "APPROVED");

        assertThat(rows).isEqualTo(0);
        assertThat(repository.findByRequestId("req-2").get().getState()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void guardedTransitionFailsWhenStateStale() {
        repository.saveAndFlush(newRequest("req-3"));

        int rows = repository.guardedTransition("req-3", "APPROVED", 0L, "EXPIRED");

        assertThat(rows).isEqualTo(0);
    }
}
