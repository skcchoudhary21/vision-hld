package com.visionbank.approval.workflow;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

public class YamlWorkflowLoader implements WorkflowLoader {

    @Override
    @SuppressWarnings("unchecked")
    public WorkflowDefinition load(String classpathResource) {
        try (InputStream in = new ClassPathResource(classpathResource).getInputStream()) {
            Map<String, Object> raw = new Yaml().load(in);

            String name = (String) raw.get("name");
            int version = (Integer) raw.get("version");
            String initial = (String) raw.get("initialState");

            List<WorkflowDefinition.StateDef> states = ((List<Map<String, String>>) raw.get("states")).stream()
                    .map(s -> new WorkflowDefinition.StateDef(s.get("id"), s.get("label")))
                    .collect(Collectors.toList());

            Set<String> terminalStates = new HashSet<>((List<String>) raw.get("terminalStates"));

            List<Transition> transitions = ((List<Map<String, Object>>) raw.get("transitions")).stream()
                    .map(this::toTransition)
                    .collect(Collectors.toList());

            Map<String, List<String>> events = raw.containsKey("events")
                    ? ((Map<String, List<String>>) raw.get("events"))
                    : Map.of();

            WorkflowDefinition definition = new WorkflowDefinition(name, version, states, initial, terminalStates, transitions, events);
            validate(definition);
            return definition;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow definition: " + classpathResource, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Transition toTransition(Map<String, Object> t) {
        List<String> guards = t.containsKey("guards") ? (List<String>) t.get("guards") : List.of();
        List<String> allowedRoles = t.containsKey("allowedRoles") ? (List<String>) t.get("allowedRoles") : List.of();
        Integer requiredApprovals = (Integer) t.get("requiredApprovals");
        return new Transition((String) t.get("name"), (String) t.get("from"), (String) t.get("to"),
                guards, allowedRoles, requiredApprovals);
    }

    private void validate(WorkflowDefinition def) {
        if (!def.hasState(def.initialState())) {
            throw new IllegalStateException("initialState " + def.initialState() + " is not declared in states[]");
        }
        Set<String> seenIdentities = new HashSet<>();
        Set<String> statesWithOutgoingTransitions = new HashSet<>();
        for (Transition t : def.transitions()) {
            if (!def.hasState(t.from()) || !def.hasState(t.to())) {
                throw new IllegalStateException("Transition " + t.name() + " references a state not in states[]");
            }
            String identity = t.name() + "|" + t.from();
            if (!seenIdentities.add(identity)) {
                throw new IllegalStateException("Duplicate transition '" + t.name() + "' from state " + t.from());
            }
            if (t.requiredApprovals() != null && t.requiredApprovals() < 1) {
                throw new IllegalStateException("Transition " + t.name() + " from " + t.from()
                        + " has requiredApprovals " + t.requiredApprovals() + ", must be >= 1 when present");
            }
            statesWithOutgoingTransitions.add(t.from());
        }
        for (WorkflowDefinition.StateDef s : def.states()) {
            boolean hasOutgoing = statesWithOutgoingTransitions.contains(s.id());
            boolean declaredTerminal = def.terminalStates().contains(s.id());
            if (hasOutgoing && declaredTerminal) {
                throw new IllegalStateException("State " + s.id() + " is declared terminal but has outgoing transitions");
            }
            if (!hasOutgoing && !declaredTerminal) {
                throw new IllegalStateException("State " + s.id() + " has no outgoing transitions but is not declared terminal");
            }
        }
    }
}
