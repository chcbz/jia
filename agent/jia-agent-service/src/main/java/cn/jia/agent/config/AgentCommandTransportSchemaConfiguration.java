package cn.jia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers D01 schema access only while the command outbox boundary is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
public class AgentCommandTransportSchemaConfiguration {
    @Bean
    @Conditional(AgentCommandLegacyTransportSchemaEnabledCondition.class)
    public AgentCommandTransportSchemaInitializer agentCommandTransportSchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new AgentCommandTransportSchemaInitializer(jdbcTemplate);
    }
}
