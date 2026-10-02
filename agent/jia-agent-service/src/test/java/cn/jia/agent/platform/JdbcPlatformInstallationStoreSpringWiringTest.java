package cn.jia.agent.platform;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JdbcPlatformInstallationStoreSpringWiringTest {
    @Test
    void enabledRepositorySupportsClassBasedPersistenceExceptionTranslation() {
        try (AnnotationConfigApplicationContext context = contextWithPlatformSkillsEnabled(true)) {
            PlatformInstallationStore store = context.getBean(PlatformInstallationStore.class);

            assertTrue(AopUtils.isCglibProxy(store));
            assertSame(JdbcPlatformInstallationStore.class, AopUtils.getTargetClass(store));
            assertThrows(IllegalArgumentException.class,
                    () -> store.terminalDeliveryCandidates(null, -1, 1));
        }
    }

    @Test
    void disabledRepositoryBacksOffWithoutCreatingPlatformStore() {
        try (AnnotationConfigApplicationContext context = contextWithPlatformSkillsEnabled(false)) {
            assertTrue(context.getBeansOfType(JdbcPlatformInstallationStore.class).isEmpty());
            assertTrue(context.getBeansOfType(PlatformInstallationStore.class).isEmpty());
        }
    }

    private static AnnotationConfigApplicationContext contextWithPlatformSkillsEnabled(boolean enabled) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "jdbcPlatformInstallationStoreSpringWiringTest",
                Map.of("agent.platform-skills.enabled", Boolean.toString(enabled))));
        context.register(Wiring.class, JdbcPlatformInstallationStore.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    static class Wiring {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        static PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslationPostProcessor() {
            PersistenceExceptionTranslationPostProcessor processor =
                    new PersistenceExceptionTranslationPostProcessor();
            processor.setProxyTargetClass(true);
            return processor;
        }
    }
}
