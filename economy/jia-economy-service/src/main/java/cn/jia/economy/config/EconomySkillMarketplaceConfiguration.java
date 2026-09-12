package cn.jia.economy.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Auto-scanned, independently gated W07 schema configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EconomySkillSchemaProperties.class)
public class EconomySkillMarketplaceConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "economy.skill.schema", name = "enabled", havingValue = "true")
    public EconomySkillSchemaInitializer economySkillSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new EconomySkillSchemaInitializer(jdbcTemplate);
    }
}
