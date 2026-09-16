package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** R2 schema is installed only together with the explicitly enabled HTTP feature. */
@Configuration(proxyBeanMethods = false)
public class AgentTaskFormalDeliveryConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "jia.agent.formal-delivery", name = "enabled", havingValue = "true")
    public AgentTaskFormalDeliverySchemaInitializer agentTaskFormalDeliverySchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new AgentTaskFormalDeliverySchemaInitializer(jdbcTemplate);
    }
}
