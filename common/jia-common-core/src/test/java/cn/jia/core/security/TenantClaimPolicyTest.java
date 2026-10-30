package cn.jia.core.security;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantClaimPolicyTest {
    @Test void canonicalPartitionIsNotLimitedToProductionOrGrayNumbers() {
        for (String tenant : List.of("0", "1", "tenant-blue")) {
            assertEquals(tenant, TenantClaimPolicy.resolve(Map.of("tenant_id", tenant)));
            assertEquals(tenant, TenantClaimPolicy.resolve(Map.of(
                    "tenant_id", tenant, "tenant_claim_version", "1")));
        }
    }

    @Test void identityOrAliasNeverSuppliesAMissingCanonicalPartition() {
        for (Map<String, Object> claims : List.<Map<String, Object>>of(
                Map.of(), Map.of("jiacn", "owner"), Map.of("token_kind", "user", "jiacn", "owner"),
                Map.of("tenantId", "0"), Map.of("tenantId", "1", "jiacn", "owner"),
                Map.of("tenant_claim_version", "1"))) {
            assertThrows(IllegalArgumentException.class, () -> TenantClaimPolicy.resolve(claims));
        }
        assertThrows(IllegalArgumentException.class, () -> TenantClaimPolicy.resolve(null));
    }

    @Test void canonicalClaimCannotBeCoercedPaddedOrContradicted() {
        for (Map<String, Object> claims : List.<Map<String, Object>>of(
                Map.of("tenant_id", 1), Map.of("tenant_id", ""), Map.of("tenant_id", " 1"),
                Map.of("tenant_id", "1 "), Map.of("tenant_id", "1\n"),
                Map.of("tenant_id", "1", "tenantId", "0"),
                Map.of("tenant_id", "1", "tenant_claim_version", "2"))) {
            assertThrows(IllegalArgumentException.class, () -> TenantClaimPolicy.resolve(claims));
        }
        // A duplicate assertion may agree; it is not a second source of tenant authority.
        assertEquals("1", TenantClaimPolicy.resolve(Map.of("tenant_id", "1", "tenantId", "1")));
    }
}
