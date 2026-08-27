package com.visionbank.approval.messaging;

import com.visionbank.approval.policy.PolicyResolutionDto;
import com.visionbank.approval.policy.PolicyRuleResolutionService;
import com.visionbank.approval.service.ApprovalCommandService;
import com.visionbank.approval.service.CreateApprovalRequest;
import jakarta.annotation.PostConstruct;
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

import java.time.Instant;

@Component
public class SubmissionCommandConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(SubmissionCommandConsumer.class);
    private static final String CONSUMER_NAME = "approval-engine-1";

    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final StringRedisTemplate redisTemplate;
    private final PolicyRuleResolutionService policyRuleResolutionService;
    private final ApprovalCommandService commandService;
    private final LifecycleEventPublisher lifecycleEventPublisher;

    public SubmissionCommandConsumer(StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
                                      StringRedisTemplate redisTemplate,
                                      PolicyRuleResolutionService policyRuleResolutionService,
                                      ApprovalCommandService commandService,
                                      LifecycleEventPublisher lifecycleEventPublisher) {
        this.container = container;
        this.redisTemplate = redisTemplate;
        this.policyRuleResolutionService = policyRuleResolutionService;
        this.commandService = commandService;
        this.lifecycleEventPublisher = lifecycleEventPublisher;
    }

    @PostConstruct
    public void subscribe() {
        try {
            redisTemplate.opsForStream().createGroup(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                    RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP);
        } catch (Exception e) {
            // BUSYGROUP: already exists from a previous run -- fine.
        }
        StreamMessageListenerContainer.StreamReadRequest<String> readRequest = StreamMessageListenerContainer.StreamReadRequest
                .builder(StreamOffset.create(RedisStreamNames.SUBMISSION_COMMAND_STREAM, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, CONSUMER_NAME))
                // ConsumerStreamReadRequestBuilder defaults autoAck to TRUE (confirmed by
                // decompiling spring-data-redis 4.1.1) -- the 3-arg receive(Consumer,
                // StreamOffset, StreamListener) overload this replaces explicitly overrides
                // that default to false. Without this override every message would be
                // acknowledged the instant it's read, before onMessage() even runs, silently
                // defeating the manual ack-on-success / leave-pending-on-failure contract
                // this class and the reconciler both depend on.
                .autoAcknowledge(false)
                // Default StreamMessageListenerContainer behavior cancels the subscription
                // PERMANENTLY on any error escaping the read loop (confirmed by decompiling
                // spring-data-redis 4.1.1: StreamReadRequestBuilder's default
                // cancelSubscriptionOnError predicate is `t -> true`) -- a single transient
                // Redis blip would silently and permanently stop this consumer for the life
                // of the JVM, with no reconciler able to recover it. Never cancel; per-message
                // failures are already handled by onMessage()'s own try/catch.
                .cancelOnError(t -> false)
                .build();
        container.register(readRequest, this);
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        var fields = record.getValue();
        String transferId = fields.get("transferId");
        try {
            PolicyResolutionDto resolution = policyRuleResolutionService.resolve(Long.parseLong(fields.get("amountMinorUnits")));
            CreateApprovalRequest cmd = new CreateApprovalRequest(
                    transferId, "TRANSFER_APPROVAL", fields.get("makerId"),
                    resolution.workflowId(), resolution.workflowVersion(), "v1",
                    "{\"transferId\":\"" + transferId + "\",\"amount\":" + fields.get("amountMinorUnits") + "}",
                    Instant.parse(fields.get("expiresAt")));
            commandService.create(cmd, transferId); // transferId doubles as the idempotency key, same as the old HTTP call did
            redisTemplate.opsForStream().acknowledge(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                    RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, record.getId());
        } catch (Exception e) {
            // Not acknowledged: Task 10's reconciler retries via XAUTOCLAIM after a timeout,
            // up to a delivery-count limit, before giving up and publishing ApprovalCreationFailed.
            log.warn("Failed to create approval request for transfer {}: {}", transferId, e.getMessage());
        }
    }
}
