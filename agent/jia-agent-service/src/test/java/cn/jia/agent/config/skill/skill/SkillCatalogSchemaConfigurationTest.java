package cn.jia.agent.config.skill.skill;

import cn.jia.agent.config.skill.SkillCatalogSchemaConfiguration;
import cn.jia.agent.config.skill.SkillSchemaInitializer;
import cn.jia.agent.config.skill.SkillSchemaProperties;
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

class SkillCatalogSchemaConfigurationTest {
    private static final ApplicationContextRunner CONFIGURATION_RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(SkillCatalogSchemaConfiguration.class);
    private static final ApplicationContextRunner PROPERTIES_RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void enabledSchemaFlagAloneDoesNotActivateMaintenanceOrRequireJdbc() {
        CONFIGURATION_RUNNER.withPropertyValues("economy.skill.schema.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(SkillSchemaInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
        });
    }

    @Test
    void schemaGateDefaultsFalseAndNeedsNoJdbcBeans() {
        CONFIGURATION_RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(SkillSchemaProperties.class).enabled());
            assertTrue(context.getBeansOfType(SkillSchemaInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
        });
    }

    @Test
    void purePropertyBindingDoesNotActivateConfigurationOrRequireJdbc() {
        PROPERTIES_RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(SkillSchemaProperties.class).enabled());
            assertTrue(context.getBeansOfType(SkillSchemaInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
        });
        PROPERTIES_RUNNER.withPropertyValues("economy.skill.schema.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(SkillSchemaProperties.class).enabled());
            assertTrue(context.getBeansOfType(SkillSchemaInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
        });
    }

    @Test
    void enabledSchemaWithoutJdbcFailsClosedAtWiring() {
        CONFIGURATION_RUNNER.withPropertyValues("economy.skill.schema.enabled=true", "spring.profiles.active=schema-maintenance").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(messages(failure).contains("JdbcTemplate"), messages(failure));
        });
    }

    @Test
    void enabledSchemaRejectsH2RatherThanSilentlyApplyingMysqlDdl() {
        CONFIGURATION_RUNNER
                .withUserConfiguration(H2Jdbc.class)
                .withPropertyValues("economy.skill.schema.enabled=true", "spring.profiles.active=schema-maintenance")
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
    @EnableConfigurationProperties(SkillSchemaProperties.class)
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
