package com.visionbank.approval.web.dto;

import java.util.List;

public record AvailableActionDto(String name, List<String> allowedRoles,
                                  Integer requiredApprovals, Integer currentApprovals) {}
