package cn.jia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class AgentRuntimeV1SchemaConfiguration {
    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean
    public AgentRuntimeV1SchemaInitializer agentRuntimeV1SchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new AgentRuntimeV1SchemaInitializer(jdbcTemplate);
    }
}
