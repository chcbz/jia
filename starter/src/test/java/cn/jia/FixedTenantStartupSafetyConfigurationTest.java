package cn.jia;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FixedTenantStartupSafetyConfigurationTest {
    private static final AtomicInteger MUTATIONS = new AtomicInteger();

    @Test void fixedModeRemovesSchemaInitializersBeforeInstantiation() {
        MUTATIONS.set(0);
        try (var context = context(Map.of("cyf.security.fixed-tenant.enabled", "true"),
                FixedTenantStartupSafetyConfiguration.class, SyntheticSchemaConfiguration.class)) {
            assertEquals(0, MUTATIONS.get());
            assertFalse(context.containsBean("syntheticSchemaInitializer"));
        }
    }

    @Test void fixedModeFailsClosedIfStartupSchemaWritesAreExplicitlyRequested() {
        var failure = assertThrows(RuntimeException.class, () -> context(Map.of(
                        "cyf.security.fixed-tenant.enabled", "true",
                        "cyf.startup.schema-writes-enabled", "true"),
                FixedTenantStartupSafetyConfiguration.class));
        assertTrue(rootMessage(failure).contains("cannot enable startup schema writes"));
    }


    @Test void fixedModeRejectsSchemaMaintenanceProfileBeforeDependencyResolution() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("schema-maintenance");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "cyf.security.fixed-tenant.enabled", "true")));
        context.register(FixedTenantStartupSafetyConfiguration.class, SyntheticSchemaConfiguration.class);
        var failure = assertThrows(RuntimeException.class, context::refresh);
        assertTrue(rootMessage(failure).contains("cannot activate schema-maintenance profile"));
        context.close();
    }

    @Test void fixedModeDoesNotRegisterScheduledJobProcessorWhileNormalModeStillDoes() {
        try (var fixed = context(Map.of("cyf.security.fixed-tenant.enabled", "true"),
                BackgroundSchedulingConfiguration.class)) {
            assertTrue(fixed.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
        }
        try (var normal = context(Map.of("cyf.security.fixed-tenant.enabled", "false"),
                BackgroundSchedulingConfiguration.class)) {
            assertEquals(1, normal.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size());
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... classes) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(classes);
        context.refresh();
        return context;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return String.valueOf(cursor.getMessage());
    }

    @Configuration(proxyBeanMethods = false)
    static class SyntheticSchemaConfiguration {
        @Bean SyntheticSchemaInitializer syntheticSchemaInitializer() { return new SyntheticSchemaInitializer(); }
    }
    static final class SyntheticSchemaInitializer implements InitializingBean {
        @Override public void afterPropertiesSet() { MUTATIONS.incrementAndGet(); }
    }
}
