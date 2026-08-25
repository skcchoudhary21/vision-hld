package com.visionbank.transfer.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "transfer")
@Getter
@Setter
public class Transfer {

    @Id
    @Column(name = "transfer_id")
    private String transferId;

    @Column(name = "maker_id", nullable = false)
    private String makerId;

    @Column(name = "from_account", nullable = false)
    private String fromAccount;

    @Column(name = "to_account", nullable = false)
    private String toAccount;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private TransferState state;

    @Column(name = "approval_request_id")
    private String approvalRequestId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    // Persisted once at submission and reused on any retry (Task 13) — never
    // recomputed with Instant.now() again, or a retry's engine call would
    // carry a different expiresAt than the original, which the engine's
    // idempotency hash would see as a body mismatch (spurious 409).
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
