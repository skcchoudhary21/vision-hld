package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "approval_decision", uniqueConstraints = @UniqueConstraint(columnNames = {"request_id", "actor_id"}))
@Getter
@Setter
public class ApprovalDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "decision_id")
    private String decisionId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "actor_role", nullable = false)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    private DecisionType decision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum DecisionType { APPROVE, REJECT }
}
