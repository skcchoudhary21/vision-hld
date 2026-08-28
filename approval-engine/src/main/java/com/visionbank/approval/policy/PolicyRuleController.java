package com.visionbank.approval.policy;

import com.visionbank.approval.service.InvalidRequestException;
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
        requireNonOverlapping(body);
        rules.deleteAllInBatch();
        List<PolicyRule> saved = rules.saveAll(body.stream()
                .map(d -> new PolicyRule(null, d.minAmountMinorUnits(), d.maxAmountMinorUnits(), d.workflowId(), d.workflowVersion()))
                .toList());
        return saved.stream().map(this::toDto).toList();
    }

    private void requireNonOverlapping(List<PolicyRuleDto> body) {
        for (int i = 0; i < body.size(); i++) {
            for (int j = i + 1; j < body.size(); j++) {
                if (overlaps(body.get(i), body.get(j))) {
                    throw new InvalidRequestException("Overlapping policy rule ranges: ["
                            + body.get(i).minAmountMinorUnits() + ", " + body.get(i).maxAmountMinorUnits() + "] and ["
                            + body.get(j).minAmountMinorUnits() + ", " + body.get(j).maxAmountMinorUnits() + "]");
                }
            }
        }
    }

    private boolean overlaps(PolicyRuleDto a, PolicyRuleDto b) {
        boolean aEndsBeforeBStarts = a.maxAmountMinorUnits() != null && a.maxAmountMinorUnits() < b.minAmountMinorUnits();
        boolean bEndsBeforeAStarts = b.maxAmountMinorUnits() != null && b.maxAmountMinorUnits() < a.minAmountMinorUnits();
        return !(aEndsBeforeBStarts || bEndsBeforeAStarts);
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
