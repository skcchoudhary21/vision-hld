package com.visionbank.banking.corebanking;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake core banking, allowed per the assignment ("core banking ... can be
 * stubbed or mocked"). Not a networked service — a bean inside this app.
 * A real implementation would swap this for an HTTP/gRPC client behind the
 * same CoreBankingClient interface.
 */
@Component
public class StubCoreBankingClient implements CoreBankingClient {

    private static final long STUB_BALANCE_CEILING = 1_000_000_00L;
    private static final long STUB_LIMIT_CEILING = 500_000_00L;

    private final Set<String> seenDuplicateKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> releaseCounts = new ConcurrentHashMap<>();

    @Override
    public ValidationResult validate(String fromAccount, long amountMinorUnits, String duplicateKey) {
        boolean sufficientBalance = amountMinorUnits <= STUB_BALANCE_CEILING;
        boolean withinLimit = amountMinorUnits <= STUB_LIMIT_CEILING;
        boolean duplicate = !seenDuplicateKeys.add(duplicateKey);
        return new ValidationResult(sufficientBalance, withinLimit, duplicate);
    }

    @Override
    public synchronized boolean release(String transferId, String fromAccount, long amountMinorUnits) {
        // Fulfills the interface's idempotent-by-transferId contract: a second
        // call for a transferId already released is a no-op, not a second movement.
        releaseCounts.computeIfAbsent(transferId, id -> new AtomicInteger(0)).compareAndSet(0, 1);
        return true;
    }

    public int releaseCountFor(String transferId) {
        AtomicInteger count = releaseCounts.get(transferId);
        return count == null ? 0 : count.get();
    }
}
