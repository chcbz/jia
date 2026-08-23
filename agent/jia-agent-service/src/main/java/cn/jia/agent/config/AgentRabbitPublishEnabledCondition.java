package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Side-effect-free condition for a dependency-complete D03 relay/publisher boundary. */
final class AgentRabbitPublishEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Binder binder = Binder.get(context.getEnvironment());
        return enabled(binder, "agent.command-outbox.enabled")
                && enabled(binder, "agent.rabbit-topology.enabled")
                && enabled(binder, "agent.rabbit-publish.enabled");
    }

    private static boolean enabled(Binder binder, String property) {
        return binder.bind(property, Bindable.of(Boolean.class)).orElse(false);
    }
}
