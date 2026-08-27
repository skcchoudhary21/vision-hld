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
        container.receive(Consumer.from(RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, CONSUMER_NAME),
                StreamOffset.create(RedisStreamNames.SUBMISSION_COMMAND_STREAM, ReadOffset.lastConsumed()), this);
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
