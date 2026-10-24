package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers legacy Rabbit consume/recovery only for the legacy consumer lane. */
public final class AgentLegacyRabbitDispatchEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Binder binder = Binder.get(context.getEnvironment());
        return enabled(binder, "agent.rabbit-dispatch.enabled")
                && enabled(binder, "agent.rabbit-consume.enabled")
                && !enabled(binder, "agent.rabbit-consume-v2.enabled");
    }

    private static boolean enabled(Binder binder, String property) {
        return binder.bind(property, Bindable.of(Boolean.class)).orElse(false);
    }
}
