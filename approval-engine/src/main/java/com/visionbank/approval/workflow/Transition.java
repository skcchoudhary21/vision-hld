package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;

public record Transition(String name, ApprovalState from, ApprovalState to, String guard) {}
