package com.visionbank.banking.ui;

import java.util.List;

public record StageViewDto(String id, String label, String status,
                            Integer requiredApprovals, Integer completedApprovals,
                            List<AuditEntryDto> approvals) {}
