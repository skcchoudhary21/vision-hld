package com.visionbank.approval.domain;

import java.util.List;

public record PolicySnapshot(
        String policyVersion,
        int requiredApprovals,
        List<String> eligibleRoles,
        boolean makerCanApprove) {}
