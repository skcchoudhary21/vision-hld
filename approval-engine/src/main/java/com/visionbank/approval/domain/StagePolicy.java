package com.visionbank.approval.domain;

import java.util.List;

public record StagePolicy(int requiredApprovals, List<String> eligibleRoles) {}
