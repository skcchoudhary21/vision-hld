package com.visionbank.banking.ui;

import java.util.List;

public record WorkflowTransitionDto(String name, String from, String to, List<String> guards,
                                     List<String> allowedRoles, Integer requiredApprovals) {}
