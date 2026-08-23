package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Uses the immutable M3 Boolean binder semantics and performs no transport access. */
final class AgentRabbitConsumeEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("agent.rabbit-consume",
                        Bindable.of(AgentRabbitSafetyProperties.RabbitConsume.class))
                .map(AgentRabbitSafetyProperties.RabbitConsume::enabled)
                .orElse(false);
    }
}
