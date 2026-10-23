package cn.jia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers the additive Protocol v1 report receipt schema independently of workspace storage. */
@Configuration(proxyBeanMethods = false)
@Profile("!local-test-area")
public class AgentExecutionReportSchemaConfiguration {
    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean
    public AgentExecutionReportSchemaInitializer agentExecutionReportSchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new AgentExecutionReportSchemaInitializer(jdbcTemplate);
    }
}
