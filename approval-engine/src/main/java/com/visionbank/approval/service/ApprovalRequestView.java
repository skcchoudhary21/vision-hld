package com.visionbank.approval.service;

public record ApprovalRequestView(String requestId, String state, long version) {}
