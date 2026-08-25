package com.visionbank.approval.workflow;

import com.visionbank.approval.domain.ApprovalState;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class YamlWorkflowLoader implements WorkflowLoader {

    @Override
    @SuppressWarnings("unchecked")
    public WorkflowDefinition load(String classpathResource) {
        try (InputStream in = new ClassPathResource(classpathResource).getInputStream()) {
            Map<String, Object> raw = new Yaml().load(in);

            String name = (String) raw.get("name");
            int version = (Integer) raw.get("version");
            List<ApprovalState> states = ((List<String>) raw.get("states")).stream()
                    .map(ApprovalState::valueOf)
                    .collect(Collectors.toList());
            ApprovalState initial = ApprovalState.valueOf((String) raw.get("initialState"));

            List<Transition> transitions = ((List<Map<String, String>>) raw.get("transitions")).stream()
                    .map(t -> new Transition(
                            t.get("name"),
                            ApprovalState.valueOf(t.get("from")),
                            ApprovalState.valueOf(t.get("to")),
                            t.get("guard")))
                    .collect(Collectors.toList());

            WorkflowDefinition definition = new WorkflowDefinition(name, version, states, initial, transitions);
            validate(definition);
            return definition;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow definition: " + classpathResource, e);
        }
    }

    private void validate(WorkflowDefinition def) {
        if (!def.states().contains(def.initialState())) {
            throw new IllegalStateException("initialState " + def.initialState() + " is not declared in states[]");
        }
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (Transition t : def.transitions()) {
            if (!def.states().contains(t.from()) || !def.states().contains(t.to())) {
                throw new IllegalStateException("Transition " + t.name() + " references a state not in states[]");
            }
            if (!seenNames.add(t.name())) {
                throw new IllegalStateException("Duplicate transition name: " + t.name());
            }
        }
    }
}
