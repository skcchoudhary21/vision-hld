package com.visionbank.approval.policy;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "policy_rule")
@Getter
@NoArgsConstructor
public class PolicyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "min_amount_minor_units", nullable = false)
    private long minAmountMinorUnits;

    // null = unbounded upper end of the range
    @Column(name = "max_amount_minor_units")
    private Long maxAmountMinorUnits;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;

    public PolicyRule(Long id, long minAmountMinorUnits, Long maxAmountMinorUnits, String workflowId, int workflowVersion) {
        this.id = id;
        this.minAmountMinorUnits = minAmountMinorUnits;
        this.maxAmountMinorUnits = maxAmountMinorUnits;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
    }

    public boolean covers(long amountMinorUnits) {
        return amountMinorUnits >= minAmountMinorUnits
                && (maxAmountMinorUnits == null || amountMinorUnits <= maxAmountMinorUnits);
    }
}
