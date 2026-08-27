package com.visionbank.approval.messaging;

import com.visionbank.approval.policy.PolicyResolutionDto;
import com.visionbank.approval.policy.PolicyRuleResolutionService;
import com.visionbank.approval.service.ApprovalCommandService;
import com.visionbank.approval.service.CreateApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SubmissionCommandReconciler {

    private static final Logger log = LoggerFactory.getLogger(SubmissionCommandReconciler.class);
    private static final Duration CLAIM_AFTER_IDLE = Duration.ofSeconds(30);
    private static final int MAX_DELIVERY_ATTEMPTS = 3;
    private static final String RECONCILER_CONSUMER_NAME = "approval-engine-reconciler";

    private final StringRedisTemplate redisTemplate;
    private final PolicyRuleResolutionService policyRuleResolutionService;
    private final ApprovalCommandService commandService;
    private final LifecycleEventPublisher lifecycleEventPublisher;

    public SubmissionCommandReconciler(StringRedisTemplate redisTemplate, PolicyRuleResolutionService policyRuleResolutionService,
                                        ApprovalCommandService commandService, LifecycleEventPublisher lifecycleEventPublisher) {
        this.redisTemplate = redisTemplate;
        this.policyRuleResolutionService = policyRuleResolutionService;
        this.commandService = commandService;
        this.lifecycleEventPublisher = lifecycleEventPublisher;
    }

    @Scheduled(fixedDelay = 30000)
    public void reconcileOnce() {
        reclaimAndProcess(CLAIM_AFTER_IDLE);
    }

    // Test-only: bypasses the 30s idle threshold so a test doesn't have to sleep 30s
    // to prove the reclaim mechanism works.
    void reconcileOnceForcingImmediateClaim() {
        reclaimAndProcess(Duration.ZERO);
    }

    private void reclaimAndProcess(Duration minIdleTime) {
        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(
                RedisStreamNames.SUBMISSION_COMMAND_STREAM, RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP);
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return;
        }
        PendingMessages pending = redisTemplate.opsForStream().pending(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, Range.unbounded(), 50);

        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdleTime) < 0) {
                continue;
            }
            if (message.getTotalDeliveryCount() > MAX_DELIVERY_ATTEMPTS) {
                giveUp(message.getId());
                continue;
            }
            List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                    RedisStreamNames.SUBMISSION_COMMAND_STREAM, RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP,
                    RECONCILER_CONSUMER_NAME, RedisStreamCommands.XClaimOptions.minIdle(minIdleTime).ids(message.getId()));
            for (MapRecord<String, String, String> record : claimed) {
                process(record);
            }
        }
    }

    private void process(MapRecord<String, String, String> record) {
        var fields = record.getValue();
        String transferId = fields.get("transferId");
        try {
            PolicyResolutionDto resolution = policyRuleResolutionService.resolve(Long.parseLong(fields.get("amountMinorUnits")));
            CreateApprovalRequest cmd = new CreateApprovalRequest(
                    transferId, "TRANSFER_APPROVAL", fields.get("makerId"),
                    resolution.workflowId(), resolution.workflowVersion(), "v1",
                    "{\"transferId\":\"" + transferId + "\",\"amount\":" + fields.get("amountMinorUnits") + "}",
                    Instant.parse(fields.get("expiresAt")));
            commandService.create(cmd, transferId);
            redisTemplate.opsForStream().acknowledge(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                    RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, record.getId());
        } catch (Exception e) {
            log.warn("Reconciler retry failed for transfer {}: {}", transferId, e.getMessage());
            // Left un-acked again -- either reclaimed once more next tick, or given up on
            // once its delivery count exceeds MAX_DELIVERY_ATTEMPTS.
        }
    }

    private void giveUp(RecordId recordId) {
        // Re-read the record's fields by claiming it one last time (claim also returns the payload).
        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                RedisStreamNames.SUBMISSION_COMMAND_STREAM, RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP,
                RECONCILER_CONSUMER_NAME, RedisStreamCommands.XClaimOptions.minIdle(Duration.ZERO).ids(recordId));
        for (MapRecord<String, String, String> record : claimed) {
            String transferId = record.getValue().get("transferId");
            log.error("Giving up creating approval request for transfer {} after {} delivery attempts",
                    transferId, MAX_DELIVERY_ATTEMPTS);
            lifecycleEventPublisher.publish(new ApprovalEvent(
                    UUID.randomUUID().toString(), "ApprovalCreationFailed", transferId, "{}"));
            redisTemplate.opsForStream().acknowledge(RedisStreamNames.SUBMISSION_COMMAND_STREAM,
                    RedisStreamNames.SUBMISSION_COMMAND_CONSUMER_GROUP, record.getId());
        }
    }
}
