package com.visionbank.approval.web.dto;

import java.util.List;

public record StageViewDto(String id, String label, String status,
                            Integer requiredApprovals, Integer completedApprovals,
                            List<DecisionViewDto> approvals) {}
