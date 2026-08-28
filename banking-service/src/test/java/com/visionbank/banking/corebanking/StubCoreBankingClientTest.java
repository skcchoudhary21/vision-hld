package com.visionbank.banking.corebanking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StubCoreBankingClientTest {

    private final StubCoreBankingClient client = new StubCoreBankingClient();

    @Test
    void validateReturnsSufficientBalanceForFundedAccount() {
        ValidationResult result = client.validate("ACC-FUNDED", 100_00L, "dup-key-1");
        assertThat(result.sufficientBalance()).isTrue();
        assertThat(result.withinLimit()).isTrue();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void validateFlagsInsufficientBalanceOverStubCeiling() {
        ValidationResult result = client.validate("ACC-FUNDED", 999_999_999_00L, "dup-key-2");
        assertThat(result.sufficientBalance()).isFalse();
    }

    @Test
    void validateReturnsSufficientBalanceForPrivilegedAccessTierAmount() {
        // AED 100,001 -- routes to the privileged-access policy tier (>= AED 100,000);
        // the balance ceiling must sit above every live policy tier or that tier is
        // undemoable regardless of what the workflow itself allows.
        ValidationResult result = client.validate("ACC-FUNDED", 100_001_00L, "dup-key-4");
        assertThat(result.sufficientBalance()).isTrue();
        assertThat(result.withinLimit()).isTrue();
    }

    @Test
    void validateFlagsAmountOverPerTransactionLimitButUnderBalanceCeiling() {
        // AED 500,000.01 -- over the per-transaction limit, but still well under the
        // (larger) funded-balance ceiling: the two checks are independent constraints,
        // not one masking the other.
        ValidationResult result = client.validate("ACC-FUNDED", 500_000_01L, "dup-key-5");
        assertThat(result.sufficientBalance()).isTrue();
        assertThat(result.withinLimit()).isFalse();
    }

    @Test
    void validateFlagsRepeatedDuplicateKeyOnSecondCall() {
        client.validate("ACC-FUNDED", 100_00L, "dup-key-3");
        ValidationResult second = client.validate("ACC-FUNDED", 100_00L, "dup-key-3");
        assertThat(second.duplicate()).isTrue();
    }

    @Test
    void releaseIsIdempotent_secondCallForSameTransferDoesNotMoveMoneyAgain() {
        boolean first = client.release("transfer-1", "ACC-FUNDED", 100_00L);
        boolean second = client.release("transfer-1", "ACC-FUNDED", 100_00L);

        assertThat(first).isTrue();
        assertThat(second).isTrue(); // idempotent success, not a second movement
        assertThat(client.releaseCountFor("transfer-1")).isEqualTo(1);
    }
}
