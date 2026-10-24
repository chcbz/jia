package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runtime-v2 persistence remains dormant unless a future runtime mode explicitly enables it. */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeV2PersistenceSchemaConfiguration {
    @Bean(name = "agentRuntimeV2PersistenceSchemaGate", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.runtime-v2-persistence", name = "enabled", havingValue = "true")
    AgentRuntimeV2PersistenceSchemaGate runtimeV2PersistenceSchemaGate(JdbcTemplate jdbc) {
        return new AgentRuntimeV2PersistenceSchemaGate(jdbc);
    }
}
