package com.visionbank.approval.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "approval_request")
@Getter
@Setter
public class ApprovalRequest {

    @Id
    @Column(name = "request_id")
    private String requestId;

    @Column(name = "request_type", nullable = false)
    private String requestType;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "maker_id", nullable = false)
    private String makerId;

    @Convert(converter = PolicySnapshotConverter.class)
    @Column(name = "policy_snapshot", columnDefinition = "jsonb", nullable = false)
    private PolicySnapshot policySnapshot;

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;
}
