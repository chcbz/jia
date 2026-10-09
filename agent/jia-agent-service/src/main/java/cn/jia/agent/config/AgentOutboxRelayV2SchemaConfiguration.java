package cn.jia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers the read-only relay-v2 catalog gate when Rabbit publishing is enabled. */
@Configuration(proxyBeanMethods = false)
public class AgentOutboxRelayV2SchemaConfiguration {
    @Bean(name = "agentOutboxRelayV2SchemaGate", defaultCandidate = false)
    @Conditional(AgentRabbitPublishEnabledCondition.class)
    public AgentOutboxRelayV2SchemaGate agentOutboxRelayV2SchemaGate(JdbcTemplate jdbcTemplate) {
        return new AgentOutboxRelayV2SchemaGate(jdbcTemplate);
    }
}
