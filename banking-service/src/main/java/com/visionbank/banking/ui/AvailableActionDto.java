package com.visionbank.banking.ui;

import java.util.List;

public record AvailableActionDto(String name, List<String> allowedRoles,
                                  Integer requiredApprovals, Integer currentApprovals) {}
