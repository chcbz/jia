package cn.jia.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class AgentRuntimeV1SchemaConfiguration {
    private final boolean validateOnly;

    public AgentRuntimeV1SchemaConfiguration(@Value("${cyf.schema.mode:install}") String schemaMode) {
        this.validateOnly = SchemaValidationMode.isValidateOnly(schemaMode);
    }

    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean
    public AgentRuntimeV1SchemaInitializer agentRuntimeV1SchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new AgentRuntimeV1SchemaInitializer(jdbcTemplate, validateOnly);
    }
}
