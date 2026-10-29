package cn.jia;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/** Fixed-tenant processes are read/write application nodes, never schema-maintenance or seed nodes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cyf.security.fixed-tenant.enabled", havingValue = "true")
public class FixedTenantStartupSafetyConfiguration {
    static final String SCHEMA_WRITE_PROPERTY = "cyf.startup.schema-writes-enabled";

    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor fixedTenantSchemaWriteSuppression(
            Environment environment) {
        return beanFactory -> {
            if (java.util.Arrays.asList(environment.getActiveProfiles()).contains("schema-maintenance")) {
                throw new IllegalStateException("Fixed-tenant process cannot activate schema-maintenance profile");
            }
            if (environment.getProperty(SCHEMA_WRITE_PROPERTY, Boolean.class, false)) {
                throw new IllegalStateException("Fixed-tenant process cannot enable startup schema writes");
            }
            if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
                throw new IllegalStateException("Fixed-tenant schema suppression requires a bean-definition registry");
            }
            Arrays.stream(beanFactory.getBeanDefinitionNames())
                    .filter(name -> isStartupMutationBean(beanFactory, name))
                    .forEach(registry::removeBeanDefinition);
        };
    }

    static boolean isStartupMutationBean(ConfigurableListableBeanFactory beanFactory, String beanName) {
        Class<?> type;
        try {
            type = beanFactory.getType(beanName, false);
        } catch (RuntimeException unresolved) {
            type = null;
        }
        String className = type == null ? beanFactory.getBeanDefinition(beanName).getBeanClassName() : type.getName();
        return className != null && (className.endsWith("SchemaInitializer")
                || className.endsWith("EconomyPlatformSkillSeeder"));
    }
}
