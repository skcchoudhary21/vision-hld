package com.visionbank.approval.messaging;

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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class LifecycleEventPublisherTest {

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

    @Autowired LifecycleEventPublisher publisher;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void publishAddsARecordToTheLifecycleEventStream() {
        ApprovalEvent event = new ApprovalEvent("evt-1", "ApprovalApproved", "req-1", "{\"requestId\":\"req-1\"}");

        publisher.publish(event);

        var records = redisTemplate.opsForStream().read(StreamOffset.fromStart(RedisStreamNames.LIFECYCLE_EVENT_STREAM));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue())
                .containsEntry("eventId", "evt-1")
                .containsEntry("eventType", "ApprovalApproved")
                .containsEntry("requestId", "req-1")
                .containsEntry("payload", "{\"requestId\":\"req-1\"}");
    }
}
