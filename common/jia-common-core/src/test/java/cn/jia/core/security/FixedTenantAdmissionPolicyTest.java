package cn.jia.core.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedTenantAdmissionPolicyTest {
    @Test void disabledModePreservesOnlyLegacyTenantZeroCompatibility() {
        var policy = new FixedTenantAdmissionPolicy("0", false, null, null);
        assertDoesNotThrow(() -> policy.requireAdmitted("0", null));
        assertDoesNotThrow(() -> policy.requireAdmitted("0", "production-client"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("1", "cyf-local"));
    }

    @Test void issuerPartitionComesOnlyFromProcessConfiguration() {
        var production = new FixedTenantAdmissionPolicy("0", false, null, null);
        assertEquals("0", production.tenantForClient("web-client"));
        assertEquals("0", production.businessTenantId());
        var grey = new FixedTenantAdmissionPolicy("0", true, "1", "cyf-local");
        assertEquals("1", grey.tenantForClient("cyf-local"));
        assertThrows(IllegalArgumentException.class, () -> grey.tenantForClient("web-client"));
        assertThrows(IllegalArgumentException.class, () -> production.tenantForClient("web-client "));
    }

    @Test void enabledModeAdmitsOnlyConfiguredExactTenantClientPairAndRejectsTenantZero() {
        var policy = new FixedTenantAdmissionPolicy("0", true, "1", "cyf-local");
        assertEquals("1", policy.tenantForClient("cyf-local"));
        for (String client : new String[]{"CYF-LOCAL", "cyf-local ", "other", "production-client"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("1", client));
        }
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("2", "cyf-local"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("0", "production-client"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("0", "cyf-local"));
        assertThrows(IllegalArgumentException.class, () -> policy.tenantForClient("production-client"));
    }
}
