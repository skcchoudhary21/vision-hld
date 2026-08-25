package com.visionbank.approval.repository;

import com.visionbank.approval.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    List<AuditLog> findByRequestIdOrderByCreatedAtAsc(String requestId);
}
