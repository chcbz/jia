package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers D01 schema access only while the command outbox boundary is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.command-outbox", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class AgentCommandTransportSchemaConfiguration {
    @Bean
    public AgentCommandTransportSchemaInitializer agentCommandTransportSchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new AgentCommandTransportSchemaInitializer(jdbcTemplate);
    }
}
