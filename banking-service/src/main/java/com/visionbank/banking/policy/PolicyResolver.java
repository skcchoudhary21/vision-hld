package com.visionbank.banking.policy;

import com.visionbank.banking.approval.ApprovalEngineClient;
import org.springframework.stereotype.Component;

// Approval policy rules (and the first-match-wins resolution logic) live in
// approval-engine, alongside the workflow catalog they route to -- this is
// just the client-side seam TransferSubmissionService calls through.
@Component
public class PolicyResolver {

    private final ApprovalEngineClient client;

    public PolicyResolver(ApprovalEngineClient client) {
        this.client = client;
    }

    public WorkflowSelection resolve(long amountMinorUnits) {
        return client.resolvePolicy(amountMinorUnits);
    }
}
