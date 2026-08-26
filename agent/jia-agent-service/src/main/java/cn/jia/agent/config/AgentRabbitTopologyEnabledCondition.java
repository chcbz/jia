package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Side-effect-free startup condition for the explicit D04 topology boundary. */
final class AgentRabbitTopologyEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("agent.rabbit-topology",
                        Bindable.of(AgentRabbitSafetyProperties.RabbitTopology.class))
                .map(AgentRabbitSafetyProperties.RabbitTopology::enabled)
                .orElse(false);
    }
}
