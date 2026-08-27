package com.visionbank.approval.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
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

@Testcontainers
@SpringBootTest
class SubmissionCommandReconcilerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired StringRedisTemplate redisTemplate;
    @Autowired SubmissionCommandReconciler reconciler;

    @Test
    void reclaimsAnEntryThatWasReadButNeverAcknowledged() {
        String transferId = UUID.randomUUID().toString();
        try {
            redisTemplate.opsForStream().createGroup(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                    RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP);
        } catch (Exception ignored) {}

        redisTemplate.opsForStream().add(RedisStreamNames.SUBMISSION_COMMAND_STREAM, Map.of(
                "transferId", transferId, "makerId", "maker-1",
                // Deliberately negative: PolicyRule.covers() requires amountMinorUnits >= 0 for every
                // seeded tier (including the unbounded high-value one), so this is the one value
                // guaranteed to make PolicyRuleResolutionService.resolve() throw on every attempt,
                // matching PolicyRuleResolutionServiceTest.throwsWhenNoRuleCoversTheAmount. A large
                // positive amount would NOT do this: the seeded "transfer-high-value" tier has no
                // upper bound, so it would resolve successfully and the message would be acknowledged
                // on the very first retry -- never exercising the give-up path this test targets.
                "amountMinorUnits", "-1",
                "expiresAt", Instant.now().plusSeconds(300).toString()));

        // Read it into the group under a consumer that will never ack it, simulating a crashed consumer.
        redisTemplate.opsForStream().read(Consumer.from(RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, "crashed-consumer"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(RedisStreamNames.SUBMISSION_COMMAND_STREAM, ReadOffset.lastConsumed()));

        PendingMessages before = redisTemplate.opsForStream().pending(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(before).isNotEmpty();

        // The reconciler gives up only once an entry's delivery count exceeds MAX_DELIVERY_ATTEMPTS,
        // and each call only reclaims-and-retries once (mirroring one 30s @Scheduled tick in
        // production). So reaching the give-up branch takes several ticks, not one -- call the
        // test-only immediate-claim entry point repeatedly (bounded, so a stuck test fails fast
        // instead of hanging) until the pending list drains.
        for (int attempt = 0; attempt < 10 && !redisTemplate.opsForStream()
                .pending(RedisStreamNames.SUBMISSION_COMMAND_STREAM, RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP,
                        Range.unbounded(), 10)
                .isEmpty(); attempt++) {
            reconciler.reconcileOnceForcingImmediateClaim(); // test-only entry point, see Step 2
        }

        PendingMessages after = redisTemplate.opsForStream().pending(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, Range.unbounded(), 10);
        assertThat(after).isEmpty(); // acknowledged after giving up (deliveryCount exceeded MAX_ATTEMPTS)
    }
}
