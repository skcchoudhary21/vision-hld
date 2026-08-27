package com.visionbank.banking.service;

import com.visionbank.banking.approval.ApprovalEngineClient;
import com.visionbank.banking.approval.CreateWorkflowRequest;
import com.visionbank.banking.approval.WorkflowResponse;
import com.visionbank.banking.corebanking.CoreBankingClient;
import com.visionbank.banking.corebanking.ValidationResult;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.policy.WorkflowSelection;
import com.visionbank.banking.policy.PolicyResolver;
import com.visionbank.banking.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Deliberately NOT @Transactional — this method spans an external HTTP call
// to the Approval Engine, which must never sit inside an open DB transaction.
// TransferPersistenceService owns the two actual commit points.
@Service
public class TransferSubmissionService {

    private static final int MAX_DUPLICATE_RETRIES = 10;
    private static final int DUPLICATE_RETRY_DELAY_MS = 10;

    private final TransferRepository transfers;
    private final CoreBankingClient coreBanking;
    private final PolicyResolver policyResolver;
    private final ApprovalEngineClient approvalEngineClient;
    private final TransferPersistenceService persistenceService;
    private final long approvalSlaSeconds;

    public TransferSubmissionService(TransferRepository transfers, CoreBankingClient coreBanking,
                                      PolicyResolver policyResolver, ApprovalEngineClient approvalEngineClient,
                                      TransferPersistenceService persistenceService,
                                      @Value("${transfer.approval-sla-seconds}") long approvalSlaSeconds) {
        this.transfers = transfers;
        this.coreBanking = coreBanking;
        this.policyResolver = policyResolver;
        this.approvalEngineClient = approvalEngineClient;
        this.persistenceService = persistenceService;
        this.approvalSlaSeconds = approvalSlaSeconds;
    }

    public TransferView submit(SubmitTransferCommand cmd, String idempotencyKey) {
        Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transfer t = existing.get();
            if (t.getApprovalRequestId() != null) {
                return new TransferView(t.getTransferId(), t.getState()); // fully completed already
            }
            return completeWorkflowCreation(t, cmd); // resume: same transferId, same persisted expiresAt
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
                        Transfer t = raceWinner.get();
                        if (t.getApprovalRequestId() != null) {
                            return new TransferView(t.getTransferId(), t.getState());
                        }
                        return completeWorkflowCreation(t, cmd);
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
            // Lost the race: another concurrent call with the same idempotencyKey committed
            // first. Re-read and continue from wherever that winning row actually is, rather
            // than propagate a raw constraint violation for what is, from the caller's
            // perspective, a perfectly legitimate retry.
            Transfer winner = transfers.findByIdempotencyKey(idempotencyKey).orElseThrow();
            if (winner.getApprovalRequestId() != null) {
                return new TransferView(winner.getTransferId(), winner.getState());
            }
            return completeWorkflowCreation(winner, cmd);
        }

        return completeWorkflowCreation(created, cmd);
    }

    private TransferView completeWorkflowCreation(Transfer transfer, SubmitTransferCommand cmd) {
        WorkflowSelection selection = policyResolver.resolve(cmd.amountMinorUnits());
        CreateWorkflowRequest workflowRequest = new CreateWorkflowRequest(
                transfer.getTransferId(), "TRANSFER_APPROVAL", cmd.makerId(), selection,
                "{\"transferId\":\"" + transfer.getTransferId() + "\",\"amount\":" + cmd.amountMinorUnits() + "}",
                transfer.getExpiresAt()); // persisted value — never recomputed on retry
        WorkflowResponse workflowResponse = approvalEngineClient.createWorkflow(workflowRequest, transfer.getTransferId());

        // Always PENDING_APPROVAL here regardless of workflowResponse.state() —
        // release is only ever triggered by consuming ApprovalApproved, so auto-release
        // and N-approver release share one trigger path.
        Transfer completed = persistenceService.markPendingApproval(transfer.getTransferId(), workflowResponse.requestId());
        return new TransferView(completed.getTransferId(), completed.getState());
    }
}
