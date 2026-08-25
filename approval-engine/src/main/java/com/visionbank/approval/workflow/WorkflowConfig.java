package com.visionbank.approval.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfig {

    @Bean
    public WorkflowDefinition workflowDefinition(@Value("${workflow.definition-path}") String path,
                                                   GuardRegistry guards) {
        WorkflowDefinition definition = new YamlWorkflowLoader().load(path);
        // GuardRegistry.get() already throws IllegalStateException on an unknown
        // name (Task 2) — calling it here for every transition turns "guard:
        // approval_satsified" (typo) into a startup failure instead of a
        // first-request failure.
        definition.transitions().forEach(t -> guards.get(t.guard()));
        return definition;
    }

    @Bean
    public WorkflowRegistry workflowRegistry(GuardRegistry guards) {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());
        // Same startup fail-fast as before Task 2: every guard name referenced by every
        // loaded workflow must resolve, not just the one workflow that used to exist.
        for (String id : new String[]{"transfer-approval", "privileged-access"}) {
            registry.get(id).transitions().forEach(t -> guards.get(t.guard()));
        }
        return registry;
    }

    @Bean
    public WorkflowSelector workflowSelector(WorkflowRegistry registry) {
        return new WorkflowSelector("workflow/workflow-selection.yaml", registry);
    }
}
