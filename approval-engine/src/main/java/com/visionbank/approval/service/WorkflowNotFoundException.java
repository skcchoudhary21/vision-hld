package com.visionbank.approval.service;

public class WorkflowNotFoundException extends RuntimeException {
    public WorkflowNotFoundException(String workflowId, int version) {
        super("No workflow definition for " + workflowId + ":" + version);
    }
}
