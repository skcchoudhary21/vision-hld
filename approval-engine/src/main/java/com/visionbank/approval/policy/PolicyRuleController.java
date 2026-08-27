package com.visionbank.approval.policy;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/policy-rules")
public class PolicyRuleController {

    private final PolicyRuleRepository rules;
    private final PolicyRuleResolutionService resolutionService;

    public PolicyRuleController(PolicyRuleRepository rules, PolicyRuleResolutionService resolutionService) {
        this.rules = rules;
        this.resolutionService = resolutionService;
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

    @GetMapping("/resolve")
    public PolicyResolutionDto resolve(@RequestParam long amountMinorUnits) {
        return resolutionService.resolve(amountMinorUnits);
    }

    private PolicyRuleDto toDto(PolicyRule r) {
        return new PolicyRuleDto(r.getId(), r.getMinAmountMinorUnits(), r.getMaxAmountMinorUnits(),
                r.getWorkflowId(), r.getWorkflowVersion());
    }
}
