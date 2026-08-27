package com.visionbank.banking.messaging;

import com.visionbank.banking.approval.ApprovalEventListener;
import com.visionbank.banking.approval.IncomingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import jakarta.annotation.PostConstruct;

@Component
public class LifecycleEventConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(LifecycleEventConsumer.class);
    private static final String CONSUMER_NAME = "banking-service-1"; // one fixed logical consumer per instance is enough at this scale

    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final StringRedisTemplate redisTemplate;
    private final ApprovalEventListener eventListener;

    public LifecycleEventConsumer(StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
                                   StringRedisTemplate redisTemplate, ApprovalEventListener eventListener) {
        this.container = container;
        this.redisTemplate = redisTemplate;
        this.eventListener = eventListener;
    }

    @PostConstruct
    public void subscribe() {
        try {
            redisTemplate.opsForStream().createGroup(RedisStreamNames.LIFECYCLE_EVENT_STREAM,
                    RedisStreamNames.LIFECYCLE_EVENT_CONSUMER_GROUP);
        } catch (Exception e) {
            // BUSYGROUP: group already exists from a previous run against this Redis instance -- fine, continue.
        }
        container.receive(Consumer.from(RedisStreamNames.LIFECYCLE_EVENT_CONSUMER_GROUP, CONSUMER_NAME),
                StreamOffset.create(RedisStreamNames.LIFECYCLE_EVENT_STREAM, ReadOffset.lastConsumed()), this);
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            var fields = record.getValue();
            eventListener.handle(new IncomingEvent(fields.get("eventId"), fields.get("eventType"), fields.get("requestId")));
            redisTemplate.opsForStream().acknowledge(RedisStreamNames.LIFECYCLE_EVENT_STREAM,
                    RedisStreamNames.LIFECYCLE_EVENT_CONSUMER_GROUP, record.getId());
        } catch (Exception e) {
            // Deliberately NOT acknowledged: the message stays in the consumer group's
            // pending-entries list. Task 10's reconciler reclaims and retries it after a
            // timeout via XAUTOCLAIM. ApprovalEventListener.handle() is idempotent by
            // processed_event.event_id, so redelivery is always safe, never a double-apply.
            log.warn("Failed to handle lifecycle event {}: {}", record.getId(), e.getMessage());
        }
    }
}
