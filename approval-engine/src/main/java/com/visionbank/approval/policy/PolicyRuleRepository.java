package com.visionbank.approval.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, Long> {
    List<PolicyRule> findAllByOrderByMinAmountMinorUnitsAsc();
}
