package cn.jia.economy.config.skill;

import cn.jia.economy.config.EconomySkillMarketplaceConfiguration;
import cn.jia.economy.config.EconomySkillSchemaInitializer;
import cn.jia.economy.config.EconomySkillSchemaProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySkillMarketplaceConfigurationTest {
    private static final ApplicationContextRunner CONFIGURATION_RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(EconomySkillMarketplaceConfiguration.class);
    private static final ApplicationContextRunner PROPERTIES_RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void schemaGateDefaultsFalseAndNeedsNoJdbcBeans() {
        CONFIGURATION_RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(EconomySkillSchemaProperties.class).enabled());
            assertTrue(context.getBeansOfType(EconomySkillSchemaInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
        });
    }

    @Test
    void purePropertyBindingDoesNotActivateConfigurationOrRequireJdbc() {
        PROPERTIES_RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(EconomySkillSchemaProperties.class).enabled());
            assertTrue(context.getBeansOfType(EconomySkillSchemaInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
        });
        PROPERTIES_RUNNER.withPropertyValues("economy.skill.schema.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(EconomySkillSchemaProperties.class).enabled());
            assertTrue(context.getBeansOfType(EconomySkillSchemaInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
        });
    }

    @Test
    void enabledSchemaWithoutJdbcFailsClosedAtWiring() {
        CONFIGURATION_RUNNER.withPropertyValues("economy.skill.schema.enabled=true").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(messages(failure).contains("JdbcTemplate"), messages(failure));
        });
    }

    @Test
    void enabledSchemaRejectsH2RatherThanSilentlyApplyingMysqlDdl() {
        CONFIGURATION_RUNNER
                .withUserConfiguration(H2Jdbc.class)
                .withPropertyValues("economy.skill.schema.enabled=true")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(messages(failure).contains("requires MySQL"), messages(failure));
                });
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EconomySkillSchemaProperties.class)
    static class PropertiesOnly {
    }

    @Configuration(proxyBeanMethods = false)
    static class H2Jdbc {
        @Bean
        JdbcTemplate jdbcTemplate() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:eco_v0_w07_config;MODE=MYSQL;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return new JdbcTemplate(source);
        }
    }
}
