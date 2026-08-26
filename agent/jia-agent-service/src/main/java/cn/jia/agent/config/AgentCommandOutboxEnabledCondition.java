package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Uses the M3 safety property's Boolean binder semantics for the D01 schema boundary. */
final class AgentCommandOutboxEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("agent.command-outbox",
                        Bindable.of(AgentRabbitSafetyProperties.CommandOutbox.class))
                .map(AgentRabbitSafetyProperties.CommandOutbox::enabled)
                .orElse(false);
    }
}
