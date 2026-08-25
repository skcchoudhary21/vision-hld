package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;
import java.util.List;
import java.util.stream.Collectors;

public record WorkflowDefinition(
        String name,
        int version,
        List<ApprovalState> states,
        ApprovalState initialState,
        List<Transition> transitions) {

    public List<Transition> transitionsFrom(ApprovalState state) {
        return transitions.stream()
                .filter(t -> t.from() == state)
                .collect(Collectors.toList());
    }

    public Transition byName(String name) {
        return transitions.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown transition: " + name));
    }
}
