package com.visionbank.banking.service;

import com.visionbank.banking.corebanking.CoreBankingClient;
import com.visionbank.banking.corebanking.ValidationResult;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.messaging.CreateTransferApprovalCommand;
import com.visionbank.banking.messaging.SubmissionCommandPublisher;
import com.visionbank.banking.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Deliberately NOT @Transactional -- publish() and validate() are both external calls
// (Redis, the core banking stub) that must never sit inside an open DB transaction.
// TransferPersistenceService owns the one commit point this method has left.
@Service
public class TransferSubmissionService {

    // Guards against the validation-level race: two truly concurrent callers with the
    // same idempotencyKey can both pass the initial findByIdempotencyKey check and then
    // both call coreBanking.validate(), where the stub's own duplicate-key tracking lets
    // exactly one through and flags the other duplicate=true. That loser polls briefly
    // for the winner's row to land rather than surfacing a bare "duplicate" validation
    // failure for what is, from the caller's perspective, a legitimate concurrent retry.
    private static final int MAX_DUPLICATE_RETRIES = 10;
    private static final int DUPLICATE_RETRY_DELAY_MS = 10;

    private final TransferRepository transfers;
    private final CoreBankingClient coreBanking;
    private final TransferPersistenceService persistenceService;
    private final SubmissionCommandPublisher publisher;
    private final long approvalSlaSeconds;

    public TransferSubmissionService(TransferRepository transfers, CoreBankingClient coreBanking,
                                      TransferPersistenceService persistenceService, SubmissionCommandPublisher publisher,
                                      @Value("${transfer.approval-sla-seconds}") long approvalSlaSeconds) {
        this.transfers = transfers;
        this.coreBanking = coreBanking;
        this.persistenceService = persistenceService;
        this.publisher = publisher;
        this.approvalSlaSeconds = approvalSlaSeconds;
    }

    public TransferView submit(SubmitTransferCommand cmd, String idempotencyKey) {
        Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return resumeIfNeeded(existing.get(), cmd);
        }

        ValidationResult validation = coreBanking.validate(cmd.fromAccount(), cmd.amountMinorUnits(), idempotencyKey);
        if (!validation.isValid()) {
            // If validation failed with duplicate=true, another concurrent call with the same
            // idempotencyKey may have passed validation and is inserting, or has already inserted.
            // Retry re-reading the database a few times with small delays to wait for the
            // concurrent call to complete its insert.
            if (validation.duplicate()) {
                for (int retry = 0; retry < MAX_DUPLICATE_RETRIES; retry++) {
                    Optional<Transfer> raceWinner = transfers.findByIdempotencyKey(idempotencyKey);
                    if (raceWinner.isPresent()) {
                        return resumeIfNeeded(raceWinner.get(), cmd);
                    }
                    try {
                        Thread.sleep(DUPLICATE_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw new ValidationFailedException(
                    "sufficientBalance=" + validation.sufficientBalance()
                    + " withinLimit=" + validation.withinLimit()
                    + " duplicate=" + validation.duplicate());
        }

        String transferId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(approvalSlaSeconds);
        Transfer created;
        try {
            created = persistenceService.persistCreated(transferId, cmd, idempotencyKey, expiresAt);
        } catch (DataIntegrityViolationException e) {
            Transfer winner = transfers.findByIdempotencyKey(idempotencyKey).orElseThrow();
            return resumeIfNeeded(winner, cmd);
        }

        publishCreationCommand(created);
        return new TransferView(created.getTransferId(), created.getState());
    }

    private TransferView resumeIfNeeded(Transfer t, SubmitTransferCommand cmd) {
        if (t.getApprovalRequestId() != null) {
            return new TransferView(t.getTransferId(), t.getState()); // fully completed already
        }
        // Deliberately re-publishes even if t.getState() is FAILED -- a replayed
        // idempotency key means "try this again," and a prior permanent failure
        // (Approval Engine down, say) may no longer apply; same transferId either
        // way, so this never double-creates on approval-engine's side.
        publishCreationCommand(t);
        return new TransferView(t.getTransferId(), t.getState());
    }

    private void publishCreationCommand(Transfer transfer) {
        publisher.publish(new CreateTransferApprovalCommand(
                transfer.getTransferId(), transfer.getMakerId(), transfer.getAmountMinorUnits(), transfer.getExpiresAt()));
    }
}
