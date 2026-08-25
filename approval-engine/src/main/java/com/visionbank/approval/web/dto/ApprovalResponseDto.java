package com.visionbank.approval.web.dto;

import com.visionbank.approval.domain.ApprovalState;

public record ApprovalResponseDto(String requestId, ApprovalState state, long version) {}
