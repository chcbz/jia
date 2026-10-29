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

    @Test void onlyLegacyRegisteredClientsMayOmitHistoricalTenantMetadata() {
        var legacy = new FixedTenantAdmissionPolicy("0", false, null, null);
        assertEquals("0", legacy.requireClientTenant("web-client", null));
        assertEquals("0", legacy.requireClientTenant("web-client", ""));
        assertEquals("0", legacy.requireClientTenant("mobile-client", "0"));
        for (String foreign : new String[]{"1", "0 ", " ", "2"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> legacy.requireClientTenant("web-client", foreign));
        }
        var grey = new FixedTenantAdmissionPolicy("0", true, "1", "cyf-local");
        assertThrows(IllegalArgumentException.class, () -> grey.requireClientTenant("cyf-local", null));
        assertThrows(IllegalArgumentException.class, () -> grey.requireClientTenant("cyf-local", ""));
    }

    @Test void enabledModeAdmitsOnlyConfiguredExactTenantClientPairAndRejectsTenantZero() {
        var policy = new FixedTenantAdmissionPolicy("0", true, "1", "cyf-local");
        assertEquals("1", policy.requireClientTenant("cyf-local", "1"));
        for (String client : new String[]{"CYF-LOCAL", "cyf-local ", "other", "production-client"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("1", client));
        }
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("2", "cyf-local"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("0", "production-client"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAdmitted("0", "cyf-local"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireClientTenant("production-client", "0"));
    }
}
