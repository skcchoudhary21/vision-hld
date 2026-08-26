package com.visionbank.approval.web;

import com.visionbank.approval.service.WorkflowNotFoundException;
import com.visionbank.approval.web.dto.*;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import com.visionbank.approval.workflow.WorkflowRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    private final WorkflowRegistry registry;

    public WorkflowController(WorkflowRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<WorkflowSummaryDto> list() {
        return registry.all().stream()
                .map(d -> new WorkflowSummaryDto(d.name(), d.version(), d.states().size()))
                .toList();
    }

    @GetMapping("/{workflowId}/{version}")
    public WorkflowDefinitionDto get(@PathVariable String workflowId, @PathVariable int version) {
        WorkflowDefinition def;
        try {
            def = registry.get(workflowId, version);
        } catch (IllegalStateException e) {
            throw new WorkflowNotFoundException(workflowId, version);
        }
        return toDto(def);
    }

    private WorkflowDefinitionDto toDto(WorkflowDefinition def) {
        List<WorkflowStateDto> states = def.states().stream()
                .map(s -> new WorkflowStateDto(s.id(), s.label()))
                .toList();
        List<WorkflowTransitionDto> transitions = def.transitions().stream()
                .map(this::toDto)
                .toList();
        return new WorkflowDefinitionDto(def.name(), def.version(), def.initialState(),
                new ArrayList<>(def.terminalStates()), states, transitions);
    }

    private WorkflowTransitionDto toDto(Transition t) {
        return new WorkflowTransitionDto(t.name(), t.from(), t.to(), t.guards(), t.allowedRoles(), t.requiredApprovals());
    }
}
