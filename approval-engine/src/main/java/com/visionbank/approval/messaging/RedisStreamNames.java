package com.visionbank.approval.messaging;

public final class RedisStreamNames {
    public static final String SUBMISSION_COMMAND_STREAM = "stream:transfer-approval-create";
    public static final String SUBMISSION_COMMAND_CONSUMER_GROUP = "approval-engine-workers";
    public static final String LIFECYCLE_EVENT_STREAM = "stream:approval-lifecycle-events";

    private RedisStreamNames() {}
}
