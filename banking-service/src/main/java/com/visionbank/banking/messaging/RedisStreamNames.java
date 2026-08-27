package com.visionbank.banking.messaging;

public final class RedisStreamNames {
    public static final String SUBMISSION_COMMAND_STREAM = "stream:transfer-approval-create";
    public static final String LIFECYCLE_EVENT_STREAM = "stream:approval-lifecycle-events";
    public static final String LIFECYCLE_EVENT_CONSUMER_GROUP = "banking-service-workers";

    private RedisStreamNames() {}
}
