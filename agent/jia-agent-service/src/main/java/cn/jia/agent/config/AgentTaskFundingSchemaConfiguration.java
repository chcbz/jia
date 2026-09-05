package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Enables the additive funded-task schema only with the V0 economy preview. */
@Configuration(proxyBeanMethods = false)
public class AgentTaskFundingSchemaConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
    public AgentTaskFundingSchemaInitializer agentTaskFundingSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new AgentTaskFundingSchemaInitializer(jdbcTemplate);
    }
}
