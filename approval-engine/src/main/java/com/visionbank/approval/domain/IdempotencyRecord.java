package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
@Getter
@Setter
public class IdempotencyRecord {

    @Id
    @Column(name = "idem_key")
    private String key;

    @Column(name = "command_type", nullable = false)
    private String commandType;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "result", columnDefinition = "jsonb", nullable = false)
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
