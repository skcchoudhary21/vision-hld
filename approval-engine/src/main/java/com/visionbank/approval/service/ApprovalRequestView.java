package com.visionbank.approval.service;

import com.visionbank.approval.domain.ApprovalState;

public record ApprovalRequestView(String requestId, ApprovalState state, long version) {}
