package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class AgentCommandOperationsEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("agent.rabbit-operations", Bindable.of(AgentCommandOperationsProperties.class))
                .map(AgentCommandOperationsProperties::anyEnabled)
                .orElse(false);
    }
}
