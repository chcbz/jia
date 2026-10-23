package cn.jia.config;

import cn.jia.agent.config.AgentRuntimeV1SchemaConfiguration;
import cn.jia.chat.archive.config.ArchiveBootstrap;
import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.config.ChatSchemaReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real registration conditions; no production context, connection, or schema writes. */
class SchemaStartupIsolationTest {
    @Test
    void everyInitializerAndFactoryIsExcludedFromNormalContext() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);
        Set<String> initializers = new HashSet<>();
        int factories = 0;
        try (var context = new AnnotationConfigApplicationContext()) {
            for (var resource : resolver.getResources("classpath*:cn/jia/**/*.class")) {
                var metadata = readers.getMetadataReader(resource).getAnnotationMetadata();
                String name = metadata.getClassName();
                if (name.endsWith("SchemaInitializer") && !metadata.isInterface()) {
                    assertMaintenanceProfile(metadata.getAnnotationAttributes(Profile.class.getName()), name);
                    if (initializers.add(name)) context.register(Class.forName(name, false, getClass().getClassLoader()));
                }
                for (var method : metadata.getAnnotatedMethods(Bean.class.getName())) {
                    if (method.getReturnTypeName().endsWith("SchemaInitializer")) {
                        assertMaintenanceProfile(method.getAnnotationAttributes(Profile.class.getName()),
                                name + "#" + method.getMethodName());
                        factories++;
                    }
                }
            }
            assertEquals(42, initializers.size(), "Update the explicit schema inventory when adding a module");
            assertTrue(factories > 0, "Factory registration path must be audited too");
            context.register(ArchiveBootstrap.class, ChatSchemaReadiness.class,
                    AgentRuntimeV1SchemaConfiguration.class);
            // No JdbcTemplate: an accidentally registered initializer must fail rather than silently pass.
            context.refresh();
            for (String name : initializers) {
                assertEquals(0, context.getBeanNamesForType(Class.forName(name, false,
                        getClass().getClassLoader())).length, name);
            }
            assertEquals(0, context.getBeansOfType(ArchiveBootstrap.class).size());
            assertEquals(0, context.getBeansOfType(ChatSchemaReadiness.class).size());
        }
    }

    @Test
    void maintenanceOptInRetainsExplicitArchiveInstallerWithoutRunningIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("schema-maintenance");
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.register(ArchiveSchemaInitializer.class);
            context.refresh();
            assertNotNull(context.getBean(ArchiveSchemaInitializer.class));
            verifyNoInteractions(jdbc);
        }
    }

    @Test
    void camundaNeverAutomaticallyCreatesOrUpgradesSchema() {
        var configuration = mock(org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration.class);
        var engine = mock(org.camunda.bpm.engine.ProcessEngine.class);
        when(configuration.buildProcessEngine()).thenReturn(engine);
        try (var factory = mockStatic(org.camunda.bpm.engine.ProcessEngineConfiguration.class)) {
            factory.when(org.camunda.bpm.engine.ProcessEngineConfiguration::createStandaloneProcessEngineConfiguration)
                    .thenReturn(configuration);
            assertSame(engine, new CamundaEngineConfig().processEngine("driver", "jdbc:fixture", "user", "password"));
            verify(configuration).setDatabaseSchemaUpdate(org.camunda.bpm.engine.ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE);
            verify(configuration, never()).setDatabaseSchemaUpdate(org.camunda.bpm.engine.ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        }
    }

    private static void assertMaintenanceProfile(java.util.Map<String, Object> attributes, String source) {
        assertNotNull(attributes, source);
        assertEquals(List.of("schema-maintenance"), List.of((String[]) attributes.get("value")), source);
    }
}
