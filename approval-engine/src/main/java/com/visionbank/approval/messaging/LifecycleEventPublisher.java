package com.visionbank.approval.messaging;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LifecycleEventPublisher {

    private final StringRedisTemplate redisTemplate;

    public LifecycleEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(ApprovalEvent event) {
        redisTemplate.opsForStream().add(RedisStreamNames.LIFECYCLE_EVENT_STREAM, Map.of(
                "eventId", event.eventId(),
                "eventType", event.eventType(),
                "requestId", event.requestId(),
                "payload", event.payload()));
    }
}
