package cn.jia.agent.security;

import cn.jia.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises Spring's constructor selection, not a manually constructed admission. */
class AgentRuntimeTenantAdmissionWiringTest {
    @Test
    void springWiresFixedTenantAdmissionAndPreservesRejection() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-test", Map.of(
                    "cyf.tenant.admission-mode", "fixed-tenant",
                    "cyf.tenant.fixed-tenant-id", "1",
                    "cyf.tenant.fixed-client-tenants.cyf-local", "1")));
            context.register(AgentRuntimeTenantAdmission.class);
            context.refresh();

            var admission = context.getBean(AgentRuntimeTenantAdmission.class);
            assertEquals(TenantId.of("1"), admission.requireAdmitted("1", "cyf-local"));
            assertThrows(RuntimeException.class, () -> admission.requireAdmitted("0", "cyf-local"));
            assertThrows(RuntimeException.class, () -> admission.requireAdmitted("1", "cyf"));
        }
    }

    @Test
    void springWiresDefaultPlatformAdmission() {
        try (var context = new AnnotationConfigApplicationContext(AgentRuntimeTenantAdmission.class)) {
            assertEquals(TenantId.of("0"), context.getBean(AgentRuntimeTenantAdmission.class)
                    .requireAdmitted("0", "cyf"));
        }
    }
}
