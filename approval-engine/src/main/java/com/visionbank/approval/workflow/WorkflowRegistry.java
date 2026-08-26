package com.visionbank.approval.workflow;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorkflowRegistry {

    public record WorkflowKey(String workflowId, int version) {}

    private final Map<WorkflowKey, WorkflowDefinition> byKey;

    public WorkflowRegistry(String definitionsClasspathPattern, WorkflowLoader loader) {
        this.byKey = loadAll(definitionsClasspathPattern, loader);
    }

    public WorkflowDefinition get(String workflowId, int version) {
        WorkflowKey key = new WorkflowKey(workflowId, version);
        WorkflowDefinition def = byKey.get(key);
        if (def == null) {
            throw new IllegalStateException("No workflow definition loaded for " + workflowId + ":" + version);
        }
        return def;
    }

    public Collection<WorkflowDefinition> all() {
        return byKey.values();
    }

    // Used by ExpirySweeper to build its candidate query across every loaded workflow
    // version, not just one hardcoded state.
    public List<String> allNonTerminalStates() {
        return byKey.values().stream()
                .flatMap(def -> def.states().stream())
                .map(WorkflowDefinition.StateDef::id)
                .distinct()
                .filter(id -> byKey.values().stream().noneMatch(def -> def.terminalStates().contains(id)))
                .collect(java.util.stream.Collectors.toList());
    }

    private static Map<WorkflowKey, WorkflowDefinition> loadAll(String pattern, WorkflowLoader loader) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            Map<WorkflowKey, WorkflowDefinition> result = new HashMap<>();
            for (Resource r : resources) {
                String classpathPath = "workflow/definitions/" + r.getFilename();
                WorkflowDefinition def = loader.load(classpathPath);
                WorkflowKey key = new WorkflowKey(def.name(), def.version());
                if (result.containsKey(key)) {
                    throw new IllegalStateException("Duplicate workflow definition for " + key.workflowId() + ":" + key.version());
                }
                result.put(key, def);
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("No workflow definitions found matching: " + pattern);
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan workflow definitions: " + pattern, e);
        }
    }
}
