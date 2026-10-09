package cn.jia.core.tenant;

import cn.jia.core.mybatis.TenantScopeHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantScopeValueTest {
    @Test
    void acceptsCanonicalTenantIdsOnly() {
        assertEquals("0", TenantId.of("0").value());
        assertEquals("1", TenantId.of("1").value());
        assertEquals("12345678901234567890", TenantId.of("12345678901234567890").value());
        for (String invalid : new String[]{"", " ", "01", "+1", "-1", "1 ", "1.0"}) {
            assertThrows(IllegalArgumentException.class, () -> TenantId.of(invalid));
        }
    }

    @Test
    void tenantZeroUsesTheSameExactPredicateAsEveryOtherArea() {
        assertEquals("tenant_id = #{tenantId}", TenantScopeHelper.scopeClause("tenantId"));
        assertEquals("CAST(tenant_id AS BINARY(50)) = CAST(#{tenantId} AS BINARY(50))",
                TenantScopeHelper.scopeClauseWithCast("tenantId", 50));
        assertEquals("tenant_id = #{tenantId} AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})",
                TenantScopeHelper.scopeClauseWithOctet("tenantId"));
    }

    @Test
    void businessAndWorkerScopesCannotBePartiallyConstructed() {
        assertDoesNotThrow(() -> new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"), "owner", "web"));
        assertThrows(IllegalArgumentException.class,
                () -> new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"), " ", "web"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "local", " ",
                        1, 1, 1, "agent-command", 1));
    }

    @Test
    void userAdmissionScopeRejectsMissingOrInvalidSignedIdentityVersions() {
        BusinessScope business = new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"), "owner", "web");
        assertEquals(17, new UserAdmissionScope(business, 17, 1, 2, 3, 4).userId());
        assertThrows(NullPointerException.class, () -> new UserAdmissionScope(null, 17, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new UserAdmissionScope(business, 0, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new UserAdmissionScope(business, -1, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new UserAdmissionScope(business, 17, -1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new UserAdmissionScope(business, 17, 1, -1, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new UserAdmissionScope(business, 17, 1, 2, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new UserAdmissionScope(business, 17, 1, 2, 3, -1));
    }

    @Test
    void businessScopeRejectsAmbiguousIdentityWithoutBreakingUnicodeOwners() {
        TenantId tenant = TenantId.of("1");
        DataDomainId domain = DataDomainId.of("cyf");
        assertEquals("宋江😀", new BusinessScope(tenant, domain, "宋江😀", "web").ownerJiacn());
        for (String invalid : new String[]{" owner", "owner ", "owner\u0000", "owner\n",
                "owner\uD800", "owner\uDFFF"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BusinessScope(tenant, domain, invalid, "web"));
            assertThrows(IllegalArgumentException.class,
                    () -> new BusinessScope(tenant, domain, "owner", invalid));
        }
    }
}
