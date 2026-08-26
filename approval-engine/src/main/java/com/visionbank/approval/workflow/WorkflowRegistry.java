package com.visionbank.approval.workflow;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> byId;

    public WorkflowRegistry(String definitionsClasspathPattern, WorkflowLoader loader) {
        this.byId = loadAll(definitionsClasspathPattern, loader);
    }

    public WorkflowDefinition get(String workflowId) {
        WorkflowDefinition def = byId.get(workflowId);
        if (def == null) {
            throw new IllegalStateException("No workflow definition loaded for id: " + workflowId);
        }
        return def;
    }

    public Collection<WorkflowDefinition> all() {
        return byId.values();
    }

    // Used by ExpirySweeper (Task 5) to build its candidate query across every loaded
    // workflow, not just one hardcoded state. A state id that's terminal in one workflow
    // but coincidentally shares a name with a non-terminal state in another workflow is an
    // edge case no current sample workflow hits -- both examples use disjoint state-id
    // vocabularies. Not worth solving speculatively.
    public List<String> allNonTerminalStates() {
        return byId.values().stream()
                .flatMap(def -> def.states().stream())
                .map(WorkflowDefinition.StateDef::id)
                .distinct()
                .filter(id -> byId.values().stream().noneMatch(def -> def.terminalStates().contains(id)))
                .collect(java.util.stream.Collectors.toList());
    }

    private static Map<String, WorkflowDefinition> loadAll(String pattern, WorkflowLoader loader) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            Map<String, WorkflowDefinition> result = new HashMap<>();
            for (Resource r : resources) {
                // Loader takes a classpath-relative path; PathMatchingResourcePatternResolver
                // gives absolute resource URLs, so re-derive the classpath-relative form.
                String classpathPath = "workflow/definitions/" + r.getFilename();
                WorkflowDefinition def = loader.load(classpathPath);
                result.put(def.name(), def);
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
