package cn.jia.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Registers the M3 startup gate and side-effect-free conditional broker boundary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRabbitSafetyProperties.class)
public class AgentRabbitSafetyConfiguration {
    @Bean
    public AgentRabbitSafetyGate agentRabbitSafetyGate(
            AgentRabbitSafetyProperties properties) {
        return new AgentRabbitSafetyGate(properties);
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
}
