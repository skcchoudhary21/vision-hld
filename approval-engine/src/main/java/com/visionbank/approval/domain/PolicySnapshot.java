package com.visionbank.approval.domain;

import java.util.Map;

public record PolicySnapshot(
        String policyVersion,
        Map<String, StagePolicy> stages,
        boolean makerCanApprove) {}
