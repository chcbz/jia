package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Enables the additive funded-task schema only with the V0 economy preview. */
@Configuration(proxyBeanMethods = false)
public class AgentTaskFundingSchemaConfiguration {
    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean
    @ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
    public AgentTaskFundingSchemaInitializer agentTaskFundingSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new AgentTaskFundingSchemaInitializer(jdbcTemplate);
    }

    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean
    @ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
    public AgentTaskBountyQuoteSchemaInitializer agentTaskBountyQuoteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new AgentTaskBountyQuoteSchemaInitializer(jdbcTemplate);
    }
    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean
    @org.springframework.context.annotation.DependsOn("agentTaskFundingSchemaInitializer")
    @ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
    public AgentTaskSettlementSchemaInitializer agentTaskSettlementSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new AgentTaskSettlementSchemaInitializer(jdbcTemplate);
    }
}
