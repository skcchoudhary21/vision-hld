package com.visionbank.approval.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record WorkflowDefinition(
        String name,
        int version,
        List<StateDef> states,
        String initialState,
        Set<String> terminalStates,
        List<Transition> transitions,
        Map<String, List<String>> events) {

    public record StateDef(String id, String label) {}

    public List<Transition> transitionsFrom(String state) {
        return transitions.stream()
                .filter(t -> t.from().equals(state))
                .collect(Collectors.toList());
    }

    public boolean isTerminal(String state) {
        return terminalStates.contains(state);
    }

    public List<String> eventsFor(String state) {
        return events.getOrDefault(state, List.of());
    }

    public boolean hasState(String state) {
        return states.stream().anyMatch(s -> s.id().equals(state));
    }
}
