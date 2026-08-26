package com.visionbank.approval.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfig {

    @Bean
    public WorkflowRegistry workflowRegistry(GuardRegistry guards) {
        WorkflowRegistry registry = new WorkflowRegistry("classpath:workflow/definitions/*.yaml", new YamlWorkflowLoader());
        registry.all().forEach(def -> def.transitions().forEach(t -> t.guards().forEach(guards::get)));
        return registry;
    }
}
