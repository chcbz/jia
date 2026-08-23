package cn.jia.agent.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Registers the M3 startup gate and side-effect-free conditional broker boundary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRabbitSafetyProperties.class)
@Import({AgentRabbitTopologyConfiguration.class, AgentOutboxRelayConfiguration.class})
public class AgentRabbitSafetyConfiguration {
    @Bean
    public AgentRabbitSafetyGate agentRabbitSafetyGate(
            AgentRabbitSafetyProperties properties,
            ObjectProvider<AgentRabbitDispatchScopeProperties> dispatchScopes) {
        return new AgentRabbitSafetyGate(properties, dispatchScopes.getIfAvailable());
    }

    @Bean
    public AgentRabbitReadiness agentRabbitReadiness(AgentRabbitSafetyGate gate) {
        return new AgentRabbitReadiness(gate);
    }

    @Bean
    @Conditional(AnyAgentRabbitFlagEnabledCondition.class)
    public AgentRabbitBrokerSettings agentRabbitBrokerSettings(
            AgentRabbitSafetyProperties properties,
            AgentRabbitSafetyGate gate) {
        if (!gate.brokerRequired()) {
            throw new IllegalStateException("M3 Rabbit broker boundary activated without gate");
        }
        return new AgentRabbitBrokerSettings(properties.rabbitBroker());
    }

    /** The dispatch ACL binder does not exist while dispatch is disabled. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-dispatch", name = "enabled",
            havingValue = "true")
    @EnableConfigurationProperties(AgentRabbitDispatchScopeProperties.class)
    static class DispatchScopeConfiguration {
    }
}
