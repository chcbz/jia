package cn.jia.agent.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Keeps the D01 DDL initializer out of the relay-v2 publish path. */
final class AgentCommandLegacyTransportSchemaEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Binder binder = Binder.get(context.getEnvironment());
        boolean outboxEnabled = enabled(binder, "agent.command-outbox.enabled");
        boolean relayV2Enabled = outboxEnabled
                && enabled(binder, "agent.rabbit-topology.enabled")
                && enabled(binder, "agent.rabbit-publish.enabled");
        boolean consumerV2CoreEnabled = enabled(
                binder, "agent.rabbit-consumer-v2-core.enabled");
        return outboxEnabled && !relayV2Enabled && !consumerV2CoreEnabled;
    }

    private static boolean enabled(Binder binder, String property) {
        return binder.bind(property, Bindable.of(Boolean.class)).orElse(false);
    }
}
