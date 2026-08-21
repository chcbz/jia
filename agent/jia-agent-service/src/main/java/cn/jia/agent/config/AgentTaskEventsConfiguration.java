package cn.jia.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the single immutable C05F gate at application startup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentTaskEventsProperties.class)
public class AgentTaskEventsConfiguration {
    @Bean
    public AgentTaskEventsGate agentTaskEventsGate(AgentTaskEventsProperties properties) {
        return new AgentTaskEventsGate(properties);
    }
}
