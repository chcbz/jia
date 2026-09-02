package cn.jia.economy.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EconomyPreviewProperties.class)
public class EconomyConfiguration {
    @Bean
    public EconomyPreviewGate economyPreviewGate(EconomyPreviewProperties properties) {
        return new EconomyPreviewGate(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
    public EconomySchemaInitializer economySchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new EconomySchemaInitializer(jdbcTemplate);
    }
}
