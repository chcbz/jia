package cn.jia.agent.config.skill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Auto-scanned, independently gated W07 schema configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillSchemaProperties.class)
public class SkillCatalogSchemaConfiguration {
    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean
    @ConditionalOnProperty(prefix = "economy.skill.schema", name = "enabled", havingValue = "true")
    public SkillSchemaInitializer economySkillSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new SkillSchemaInitializer(jdbcTemplate);
    }
}
