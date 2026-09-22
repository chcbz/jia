package cn.jia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers the additive Protocol v1 report receipt schema independently of workspace storage. */
@Configuration(proxyBeanMethods = false)
public class AgentExecutionReportSchemaConfiguration {
    @Bean
    public AgentExecutionReportSchemaInitializer agentExecutionReportSchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new AgentExecutionReportSchemaInitializer(jdbcTemplate);
    }
}
