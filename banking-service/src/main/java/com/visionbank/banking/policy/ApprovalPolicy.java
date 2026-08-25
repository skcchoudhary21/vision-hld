package com.visionbank.banking.policy;

import java.util.List;

public record ApprovalPolicy(int requiredApprovals, List<String> eligibleRoles, boolean makerCanApprove) {}
