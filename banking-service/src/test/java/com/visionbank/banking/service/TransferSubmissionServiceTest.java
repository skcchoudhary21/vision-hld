package com.visionbank.banking.service;

import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.messaging.RedisStreamNames;
import com.visionbank.banking.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class TransferSubmissionServiceTest {

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

    @Autowired TransferSubmissionService service;
    @Autowired TransferPersistenceService persistenceService;
    @Autowired TransferRepository transfers;
    @Autowired StringRedisTemplate redisTemplate;

    private SubmitTransferCommand smallTransfer() {
        return new SubmitTransferCommand("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");
    }

    @Test
    void submitReturnsCreatedImmediatelyAndPublishesTheCommand() {
        TransferView view = service.submit(smallTransfer(), UUID.randomUUID().toString());

        assertThat(view.state()).isEqualTo(TransferState.CREATED);
        assertThat(transfers.findById(view.transferId()).get().getState()).isEqualTo(TransferState.CREATED);

        var records = redisTemplate.opsForStream().read(StreamOffset.fromStart(RedisStreamNames.SUBMISSION_COMMAND_STREAM));
        assertThat(records).anyMatch(r -> view.transferId().equals(r.getValue().get("transferId")));
    }

    @Test
    void insufficientBalanceFailsValidationBeforePublishingAnything() {
        SubmitTransferCommand huge = new SubmitTransferCommand("maker-1", "ACC-FUNDED", "ACC-DEST", 999_999_999_00L, "AED");

        assertThatThrownBy(() -> service.submit(huge, UUID.randomUUID().toString()))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void replayingSameIdempotencyKeyReturnsSameTransferIdWithoutPublishingTwice() {
        String key = UUID.randomUUID().toString();

        TransferView first = service.submit(smallTransfer(), key);
        TransferView second = service.submit(smallTransfer(), key);

        assertThat(second.transferId()).isEqualTo(first.transferId());
    }

    @Test
    void resumingAFailedRowRePublishesTheCommand() {
        Instant fixedExpiresAt = Instant.parse("2030-01-01T00:00:00Z");
        Transfer created = persistenceService.persistCreated("resume-1", smallTransfer(), "resume-key", fixedExpiresAt);
        assertThat(created.getState()).isEqualTo(TransferState.CREATED);
        persistenceService.markFailed("resume-1");

        TransferView view = service.submit(smallTransfer(), "resume-key");

        assertThat(view.transferId()).isEqualTo("resume-1");
        var records = redisTemplate.opsForStream().read(StreamOffset.fromStart(RedisStreamNames.SUBMISSION_COMMAND_STREAM));
        assertThat(records).anyMatch(r -> "resume-1".equals(r.getValue().get("transferId"))
                && "2030-01-01T00:00:00Z".equals(r.getValue().get("expiresAt")));
    }

    @Test
    void concurrentSubmitWithSameIdempotencyKeyNeverThrowsRawConstraintViolation() throws Exception {
        String key = UUID.randomUUID().toString();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Object> attempt = () -> {
            startGate.await();
            try {
                return service.submit(smallTransfer(), key);
            } catch (Exception e) {
                return e;
            }
        };
        Future<Object> a = pool.submit(attempt);
        Future<Object> b = pool.submit(attempt);
        startGate.countDown();

        Object resultA = a.get(10, TimeUnit.SECONDS);
        Object resultB = b.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(resultA).isInstanceOf(TransferView.class);
        assertThat(resultB).isInstanceOf(TransferView.class);
        assertThat(((TransferView) resultA).transferId()).isEqualTo(((TransferView) resultB).transferId());
    }
}
