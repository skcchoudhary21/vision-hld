package com.visionbank.approval.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StandardGuards {

    @Bean
    public GuardRegistry guardRegistry() {
        return buildRegistry();
    }

    public static GuardRegistry buildRegistry() {
        GuardRegistry registry = new GuardRegistry();
        registry.register("approvals_satisfied", ctx -> ctx.currentApprovalCount() >= ctx.requiredApprovals());
        registry.register("actor_is_maker", ctx -> ctx.actorId() != null && ctx.actorId().equals(ctx.makerId()));
        registry.register("actor_is_not_maker", ctx -> ctx.actorId() == null || !ctx.actorId().equals(ctx.makerId()));
        registry.register("sla_expired", GuardContext::slaExpired);
        return registry;
    }
}
