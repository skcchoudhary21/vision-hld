package com.visionbank.banking.messaging;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SubmissionCommandPublisher {

    private final StringRedisTemplate redisTemplate;

    public SubmissionCommandPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(CreateTransferApprovalCommand command) {
        redisTemplate.opsForStream().add(RedisStreamNames.SUBMISSION_COMMAND_STREAM, Map.of(
                "transferId", command.transferId(),
                "makerId", command.makerId(),
                "amountMinorUnits", String.valueOf(command.amountMinorUnits()),
                "expiresAt", command.expiresAt().toString()));
    }
}
