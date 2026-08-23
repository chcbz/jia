package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Side-effect-free startup gate for the durable Inbox boundary. */
final class AgentRabbitConsumeEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Binder binder = Binder.get(context.getEnvironment());
        boolean consumeEnabled = binder.bind("agent.rabbit-consume",
                        Bindable.of(AgentRabbitSafetyProperties.RabbitConsume.class))
                .map(AgentRabbitSafetyProperties.RabbitConsume::enabled)
                .orElse(false);
        if (!consumeEnabled) return false;

        boolean outboxEnabled = binder.bind("agent.command-outbox",
                        Bindable.of(AgentRabbitSafetyProperties.CommandOutbox.class))
                .map(AgentRabbitSafetyProperties.CommandOutbox::enabled)
                .orElse(false);
        boolean topologyEnabled = binder.bind("agent.rabbit-topology",
                        Bindable.of(AgentRabbitSafetyProperties.RabbitTopology.class))
                .map(AgentRabbitSafetyProperties.RabbitTopology::enabled)
                .orElse(false);
        if (!outboxEnabled || !topologyEnabled) {
            throw new IllegalStateException(
                    "Invalid M3 agent Rabbit configuration: rabbit consume requires "
                            + "command outbox and topology");
        }
        return true;
    }
}
