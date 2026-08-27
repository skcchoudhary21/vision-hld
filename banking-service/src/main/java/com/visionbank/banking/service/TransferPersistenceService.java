package com.visionbank.banking.service;

import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.domain.TransferState;
import com.visionbank.banking.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Owns the two commit points TransferSubmissionService.submit() needs on
 * either side of the (non-transactional) Approval Engine HTTP call. Kept on
 * a separate bean rather than as methods called via `this.` on the
 * submission service — same self-invocation reasoning as OutboxClaimService
 * (Task 7) and ExpiryTransitionService (Task 8).
 */
@Service
public class TransferPersistenceService {

    private final TransferRepository transfers;

    public TransferPersistenceService(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Transactional
    public Transfer persistCreated(String transferId, SubmitTransferCommand cmd, String idempotencyKey, Instant expiresAt) {
        Transfer transfer = new Transfer();
        transfer.setTransferId(transferId);
        transfer.setMakerId(cmd.makerId());
        transfer.setFromAccount(cmd.fromAccount());
        transfer.setToAccount(cmd.toAccount());
        transfer.setAmountMinorUnits(cmd.amountMinorUnits());
        transfer.setCurrency(cmd.currency());
        transfer.setState(TransferState.CREATED);
        transfer.setIdempotencyKey(idempotencyKey);
        transfer.setExpiresAt(expiresAt);
        transfer.setCreatedAt(Instant.now());
        return transfers.save(transfer);
    }

    @Transactional
    public Transfer markPendingApproval(String transferId, String approvalRequestId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow();
        transfer.setApprovalRequestId(approvalRequestId);
        transfer.setState(TransferState.PENDING_APPROVAL);
        return transfers.save(transfer);
    }

    // Test-fixture helper: lets TransferSubmissionServiceTest set up a row in FAILED
    // state (the realistic "publish attempt failed, try again" state a resume needs
    // to handle, now that submit() always publishes right after persistCreated()).
    // Production code never reaches FAILED through this class -- that transition is
    // driven by ApprovalEventListener's own setState(transfer, state) helper.
    @Transactional
    public Transfer markFailed(String transferId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow();
        transfer.setState(TransferState.FAILED);
        return transfers.save(transfer);
    }
}
