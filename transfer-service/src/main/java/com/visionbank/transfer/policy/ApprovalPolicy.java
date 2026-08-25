package com.visionbank.transfer.policy;

import java.util.List;

public record ApprovalPolicy(int requiredApprovals, List<String> eligibleRoles, boolean makerCanApprove) {}
