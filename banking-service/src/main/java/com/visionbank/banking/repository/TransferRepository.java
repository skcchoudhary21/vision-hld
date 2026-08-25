package com.visionbank.banking.repository;

import com.visionbank.banking.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
    Optional<Transfer> findByApprovalRequestId(String approvalRequestId);
}
