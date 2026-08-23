package cn.jia.agent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Side-effect-free condition for registering only the dedicated M3 broker boundary. */
final class AnyAgentRabbitFlagEnabledCondition implements Condition {
    private static final String[] RABBIT_FLAGS = {
            "agent.rabbit-topology.enabled",
            "agent.rabbit-publish.enabled",
            "agent.rabbit-consume.enabled",
            "agent.rabbit-dispatch.enabled"
    };

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        for (String flag : RABBIT_FLAGS) {
            if (context.getEnvironment().getProperty(flag, Boolean.class, false)) {
                return true;
            }
        }
        return false;
    }
}
