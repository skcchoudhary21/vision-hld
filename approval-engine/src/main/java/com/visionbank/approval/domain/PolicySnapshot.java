package com.visionbank.approval.domain;

import com.visionbank.approval.workflow.WorkflowDefinition;

public record PolicySnapshot(String policyVersion, WorkflowDefinition workflow) {}
