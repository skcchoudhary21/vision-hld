package com.visionbank.approval.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfig {

    @Bean
    public WorkflowRegistry workflowRegistry(GuardRegistry guards) {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());
        // Every guard name referenced by every loaded workflow must resolve at startup,
        // not at first use of that specific workflow -- generic over whatever .all() loads,
        // not a hardcoded list of the workflows that happen to exist today.
        registry.all().forEach(def -> def.transitions().forEach(t -> guards.get(t.guard())));
        return registry;
    }

    @Bean
    public WorkflowSelector workflowSelector(WorkflowRegistry registry) {
        return new WorkflowSelector("workflow/workflow-selection.yaml", registry);
    }
}
