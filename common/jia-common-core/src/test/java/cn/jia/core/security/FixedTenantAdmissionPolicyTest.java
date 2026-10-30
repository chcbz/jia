package cn.jia.core.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedTenantAdmissionPolicyTest {
    @Test void everyConfiguredTenantUsesTheSamePolicyWithoutLegacyModes() {
        for(String tenant : new String[]{"0", "1", "production", "tenant-blue"}) {
            var policy = new FixedTenantAdmissionPolicy(tenant, null);
            assertEquals(tenant, policy.businessTenantId());
            assertEquals(tenant, policy.tenantForClient("web-client"));
            assertDoesNotThrow(() -> policy.requireAdmitted(tenant, "web-client"));
            assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("foreign", "web-client"));
            assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted(tenant, null));
        }
    }
    @Test void optionalClientRestrictionDoesNotChangeTheConfiguredPartition() {
        var policy = new FixedTenantAdmissionPolicy("tenant-blue", "cyf-local");
        assertEquals("tenant-blue", policy.tenantForClient("cyf-local"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("0", "cyf-local"));
        for(String client : new String[]{"CYF-LOCAL", "cyf-local ", "other"})
            assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("tenant-blue", client));
    }
    @Test void missingOrInvalidConfigurationNeverFallsBackToAnotherPartition() {
        for(String tenant : new String[]{null, "", " ", "tenant-blue ", "x".repeat(51)})
            assertThrows(IllegalArgumentException.class, () -> new FixedTenantAdmissionPolicy(tenant, null));
        assertThrows(IllegalArgumentException.class, () -> new FixedTenantAdmissionPolicy("tenant-blue", ""));
    }
}
