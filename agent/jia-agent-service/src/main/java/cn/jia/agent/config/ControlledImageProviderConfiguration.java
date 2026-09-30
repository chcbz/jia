package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(ControlledImageProviderProperties.class)
public class ControlledImageProviderConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "agent.controlled-image-provider", name = "enabled",
            havingValue = "true")
    public AgentTaskProviderCostConsentSchemaInitializer
            agentTaskProviderCostConsentSchemaInitializer(JdbcTemplate jdbc) {
        return new AgentTaskProviderCostConsentSchemaInitializer(jdbc);
    }
}
