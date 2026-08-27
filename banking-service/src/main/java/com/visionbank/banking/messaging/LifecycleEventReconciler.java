package com.visionbank.banking.messaging;

import com.visionbank.banking.approval.ApprovalEventListener;
import com.visionbank.banking.approval.IncomingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class LifecycleEventReconciler {

    private static final Logger log = LoggerFactory.getLogger(LifecycleEventReconciler.class);
    private static final Duration CLAIM_AFTER_IDLE = Duration.ofSeconds(30);
    private static final int MAX_DELIVERY_ATTEMPTS = 5; // higher than the submission side: no give-up state to move to, so lean toward more retries before just logging loudly
    private static final String RECONCILER_CONSUMER_NAME = "banking-service-reconciler";

    private final StringRedisTemplate redisTemplate;
    private final ApprovalEventListener eventListener;

    public LifecycleEventReconciler(StringRedisTemplate redisTemplate, ApprovalEventListener eventListener) {
        this.redisTemplate = redisTemplate;
        this.eventListener = eventListener;
    }

    @Scheduled(fixedDelay = 30000)
    public void reconcileOnce() {
        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(
                RedisStreamNames.LIFECYCLE_EVENT_STREAM, RedisStreamNames.LIFECYCLE_EVENT_CONSUMER_GROUP);
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return;
        }
        PendingMessages pending = redisTemplate.opsForStream().pending(RedisStreamNames.LIFECYCLE_EVENT_STREAM,
                RedisStreamNames.LIFECYCLE_EVENT_CONSUMER_GROUP, Range.unbounded(), 50);

        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(CLAIM_AFTER_IDLE) < 0) {
                continue;
            }
            if (message.getTotalDeliveryCount() > MAX_DELIVERY_ATTEMPTS) {
                log.error("Giving up on lifecycle event {} after {} delivery attempts -- leaving it unacknowledged for manual investigation",
                        message.getId(), MAX_DELIVERY_ATTEMPTS);
                continue; // deliberately NOT acknowledged: this is the dead-letter boundary this plan draws -- surfaced loudly, not silently dropped, and not auto-acked away
            }
            List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                    RedisStreamNames.LIFECYCLE_EVENT_STREAM, RedisStreamNames.LIFECYCLE_EVENT_CONSUMER_GROUP,
                    RECONCILER_CONSUMER_NAME, RedisStreamCommands.XClaimOptions.minIdle(CLAIM_AFTER_IDLE).ids(message.getId()));
            for (MapRecord<String, String, String> record : claimed) {
                var fields = record.getValue();
                try {
                    eventListener.handle(new IncomingEvent(fields.get("eventId"), fields.get("eventType"), fields.get("requestId")));
                    redisTemplate.opsForStream().acknowledge(RedisStreamNames.LIFECYCLE_EVENT_STREAM,
                            RedisStreamNames.LIFECYCLE_EVENT_CONSUMER_GROUP, record.getId());
                } catch (Exception e) {
                    log.warn("Reconciler retry failed for lifecycle event {}: {}", record.getId(), e.getMessage());
                }
            }
        }
    }
}
