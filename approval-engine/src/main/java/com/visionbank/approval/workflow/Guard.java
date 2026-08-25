package com.visionbank.approval.workflow;

@FunctionalInterface
public interface Guard {
    boolean evaluate(GuardContext ctx);
}
