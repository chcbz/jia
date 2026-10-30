package cn.jia.core.config;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProcessTenantConfigurationTest {
    @Test void processPolicyIsAvailableWithoutTaskModuleAndUsesConfiguredPartition() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new org.springframework.mock.env.MockEnvironment());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of(
                    "cyf.security.fixed-tenant.enabled","false",
                    "cyf.security.fixed-tenant.tenant-id","tenant-blue",
                    "cyf.security.fixed-tenant.client-id","client")));
            context.registerBean(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class);
            context.register(ProcessTenantConfiguration.class);context.refresh();
            assertEquals(1,context.getBeansOfType(FixedTenantAdmissionPolicy.class).size());
            var policy=context.getBean(FixedTenantAdmissionPolicy.class);
            assertEquals("tenant-blue",policy.businessTenantId());assertEquals("client",policy.clientId());
        }
    }
    @Test void missingTenantCannotStartEvenWithLegacyPropertiesPresent() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new org.springframework.mock.env.MockEnvironment());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("obsolete",Map.of(
                    "cyf.security.legacy-single-tenant-id","0")));
            context.registerBean(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class);
            context.register(ProcessTenantConfiguration.class);
            assertThrows(org.springframework.beans.BeansException.class,context::refresh);
        }
    }
    @Test void explicitlyConfiguredProductionTenantNeedsNoClientRestriction() {
        var policy=new ProcessTenantConfiguration().processTenantPolicy("production", "");
        assertEquals("production",policy.businessTenantId());assertNull(policy.clientId());
        assertEquals("production",policy.tenantForClient("web"));
    }
}
