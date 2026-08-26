package com.visionbank.approval.policy;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/policy-rules")
public class PolicyRuleController {

    private final PolicyRuleRepository rules;

    public PolicyRuleController(PolicyRuleRepository rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<PolicyRuleDto> list() {
        return rules.findAllByOrderByMinAmountMinorUnitsAsc().stream().map(this::toDto).toList();
    }

    @PutMapping
    @Transactional
    public List<PolicyRuleDto> replaceAll(@RequestBody List<PolicyRuleDto> body) {
        rules.deleteAllInBatch();
        List<PolicyRule> saved = rules.saveAll(body.stream()
                .map(d -> new PolicyRule(null, d.minAmountMinorUnits(), d.maxAmountMinorUnits(), d.workflowId(), d.workflowVersion()))
                .toList());
        return saved.stream().map(this::toDto).toList();
    }

    // Server-side resolution, not just the raw rule list, so the
    // first-match-wins logic lives in exactly one place (here, alongside the
    // rules themselves) rather than being re-implemented by every caller.
    @GetMapping("/resolve")
    public PolicyResolutionDto resolve(@RequestParam long amountMinorUnits) {
        return rules.findAllByOrderByMinAmountMinorUnitsAsc().stream()
                .filter(r -> r.covers(amountMinorUnits))
                .findFirst()
                .map(r -> new PolicyResolutionDto(r.getWorkflowId(), r.getWorkflowVersion()))
                .orElseThrow(() -> new PolicyRuleNotFoundException(amountMinorUnits));
    }

    private PolicyRuleDto toDto(PolicyRule r) {
        return new PolicyRuleDto(r.getId(), r.getMinAmountMinorUnits(), r.getMaxAmountMinorUnits(),
                r.getWorkflowId(), r.getWorkflowVersion());
    }
}
