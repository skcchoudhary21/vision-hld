package com.visionbank.approval.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StandardGuardsTest {

    private final GuardRegistry registry = StandardGuards.buildRegistry();

    @Test
    void approvalsSatisfiedComparesCountToRequired() {
        GuardContext under = new GuardContext("maker-1", 1, null, null, false, "PENDING_APPROVAL", 2);
        GuardContext at = new GuardContext("maker-1", 2, null, null, false, "PENDING_APPROVAL", 2);
        assertThat(registry.get("approvals_satisfied").evaluate(under)).isFalse();
        assertThat(registry.get("approvals_satisfied").evaluate(at)).isTrue();
    }

    @Test
    void actorIsMakerComparesActorIdToMakerId() {
        GuardContext ctx = new GuardContext("maker-1", 0, "maker-1", "MAKER", false, "PENDING_APPROVAL", 1);
        GuardContext other = new GuardContext("maker-1", 0, "checker-1", "TRANSFER_CHECKER", false, "PENDING_APPROVAL", 1);
        assertThat(registry.get("actor_is_maker").evaluate(ctx)).isTrue();
        assertThat(registry.get("actor_is_maker").evaluate(other)).isFalse();
    }

    @Test
    void actorIsNotMakerIsTheNegation() {
        GuardContext maker = new GuardContext("maker-1", 0, "maker-1", "TRANSFER_CHECKER", false, "PENDING_APPROVAL", 1);
        GuardContext notMaker = new GuardContext("maker-1", 0, "checker-1", "TRANSFER_CHECKER", false, "PENDING_APPROVAL", 1);
        assertThat(registry.get("actor_is_not_maker").evaluate(maker)).isFalse();
        assertThat(registry.get("actor_is_not_maker").evaluate(notMaker)).isTrue();
    }

    @Test
    void slaExpiredReflectsContextFlag() {
        GuardContext expired = new GuardContext("maker-1", 0, null, null, true, "PENDING_APPROVAL", 1);
        GuardContext notExpired = new GuardContext("maker-1", 0, null, null, false, "PENDING_APPROVAL", 1);
        assertThat(registry.get("sla_expired").evaluate(expired)).isTrue();
        assertThat(registry.get("sla_expired").evaluate(notExpired)).isFalse();
    }
}
