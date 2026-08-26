package com.visionbank.approval.workflow;

import java.util.List;

public record Transition(String name, String from, String to, List<String> guards,
                          List<String> allowedRoles, Integer requiredApprovals) {}
