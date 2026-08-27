package com.visionbank.banking.messaging;

import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

@Testcontainers
@SpringBootTest
class LifecycleEventConsumerTest {

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

    @Autowired StringRedisTemplate redisTemplate;
    @Autowired TransferRepository transfers;

    private Transfer pendingTransfer(String id) {
        Transfer t = new Transfer();
        t.setTransferId(id);
        t.setMakerId("maker-1");
        t.setFromAccount("ACC-FUNDED");
        t.setToAccount("ACC-DEST");
        t.setAmountMinorUnits(1000_00L);
        t.setCurrency("AED");
        t.setState(TransferState.PENDING_APPROVAL);
        t.setApprovalRequestId(id);
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setExpiresAt(Instant.now().plusSeconds(300));
        t.setCreatedAt(Instant.now());
        return transfers.save(t);
    }

    @Test
    void anEventPublishedToTheStreamEventuallyUpdatesTheTransfer() {
        pendingTransfer("t-consumed-1");

        redisTemplate.opsForStream().add(RedisStreamNames.LIFECYCLE_EVENT_STREAM, Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "ApprovalRejected",
                "requestId", "t-consumed-1",
                "payload", "{}"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(transfers.findById("t-consumed-1").get().getState()).isEqualTo(TransferState.REJECTED));
    }
}
