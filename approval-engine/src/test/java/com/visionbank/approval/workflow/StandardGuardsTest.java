package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.PolicySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandardGuardsTest {

    private final GuardRegistry registry = StandardGuards.buildRegistry();

    private PolicySnapshot policy(int required, boolean makerCanApprove) {
        return new PolicySnapshot("v1", required, List.of("TRANSFER_CHECKER"), makerCanApprove);
    }

    @Test
    void noApprovalRequiredWhenRequiredApprovalsIsZero() {
        GuardContext ctx = new GuardContext("maker-1", policy(0, false), 0, null, null, false);
        assertThat(registry.get("no_approval_required").evaluate(ctx)).isTrue();
        assertThat(registry.get("approval_required").evaluate(ctx)).isFalse();
    }

    @Test
    void approvalRequiredWhenRequiredApprovalsPositive() {
        GuardContext ctx = new GuardContext("maker-1", policy(2, false), 0, null, null, false);
        assertThat(registry.get("approval_required").evaluate(ctx)).isTrue();
        assertThat(registry.get("no_approval_required").evaluate(ctx)).isFalse();
    }

    @Test
    void approvalsSatisfiedComparesCountToRequired() {
        GuardContext under = new GuardContext("maker-1", policy(2, false), 1, null, null, false);
        GuardContext at = new GuardContext("maker-1", policy(2, false), 2, null, null, false);
        assertThat(registry.get("approvals_satisfied").evaluate(under)).isFalse();
        assertThat(registry.get("approvals_satisfied").evaluate(at)).isTrue();
    }

    @Test
    void actorIsMakerComparesActorIdToMakerId() {
        GuardContext ctx = new GuardContext("maker-1", policy(1, false), 0, "maker-1", "MAKER", false);
        GuardContext other = new GuardContext("maker-1", policy(1, false), 0, "checker-1", "TRANSFER_CHECKER", false);
        assertThat(registry.get("actor_is_maker").evaluate(ctx)).isTrue();
        assertThat(registry.get("actor_is_maker").evaluate(other)).isFalse();
    }

    @Test
    void actorIsEligibleCheckerComparesRoleToPolicyRoles() {
        GuardContext eligible = new GuardContext("maker-1", policy(1, false), 0, "checker-1", "TRANSFER_CHECKER", false);
        GuardContext ineligible = new GuardContext("maker-1", policy(1, false), 0, "someone", "AUDITOR", false);
        assertThat(registry.get("actor_is_eligible_checker").evaluate(eligible)).isTrue();
        assertThat(registry.get("actor_is_eligible_checker").evaluate(ineligible)).isFalse();
    }

    @Test
    void slaExpiredReflectsContextFlag() {
        GuardContext expired = new GuardContext("maker-1", policy(1, false), 0, null, null, true);
        GuardContext notExpired = new GuardContext("maker-1", policy(1, false), 0, null, null, false);
        assertThat(registry.get("sla_expired").evaluate(expired)).isTrue();
        assertThat(registry.get("sla_expired").evaluate(notExpired)).isFalse();
    }
}
