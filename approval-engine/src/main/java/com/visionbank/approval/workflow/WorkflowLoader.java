package com.visionbank.approval.workflow;

public interface WorkflowLoader {
    WorkflowDefinition load(String classpathResource);
}
