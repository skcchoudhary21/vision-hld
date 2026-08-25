package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "audit_id")
    private String auditId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "previous_state", nullable = false)
    private String previousState;

    @Column(name = "new_state", nullable = false)
    private String newState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "metadata")
    private String metadata;
}
