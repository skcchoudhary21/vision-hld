package com.visionbank.banking.domain;

import com.visionbank.banking.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class TransferStateTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TransferRepository transfers;

    @Test
    void failedStateRoundTripsThroughPersistence() {
        Transfer t = new Transfer();
        t.setTransferId(UUID.randomUUID().toString());
        t.setMakerId("maker-1");
        t.setFromAccount("ACC-FUNDED");
        t.setToAccount("ACC-DEST");
        t.setAmountMinorUnits(1000_00L);
        t.setCurrency("AED");
        t.setState(TransferState.FAILED);
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setExpiresAt(Instant.now().plusSeconds(300));
        t.setCreatedAt(Instant.now());
        transfers.save(t);

        assertThat(transfers.findById(t.getTransferId()).get().getState()).isEqualTo(TransferState.FAILED);
    }
}
