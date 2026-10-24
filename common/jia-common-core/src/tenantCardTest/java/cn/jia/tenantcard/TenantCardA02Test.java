package cn.jia.tenantcard;

import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCardA02Test {
    private static final long USER_ID = 101;
    private static final long AUTH_EPOCH = 7;
    private static final BusinessScope BUSINESS_SCOPE = new BusinessScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "owner-a", "client-a");

    @Test
    void basicScopeCarriesNoPlatformVersionsAndLegacyAccessorsFailClosed() {
        UserAdmissionScope scope = UserAdmissionScope.basic(BUSINESS_SCOPE, USER_ID, AUTH_EPOCH);

        assertSame(BUSINESS_SCOPE, scope.businessScope());
        assertEquals(USER_ID, scope.userId());
        assertEquals(AUTH_EPOCH, scope.authEpoch());
        assertTrue(scope.platformVersions().isEmpty());
        assertThrows(IllegalStateException.class, scope::tenantSessionEpoch);
        assertThrows(IllegalStateException.class, scope::membershipRevision);
        assertThrows(IllegalStateException.class, scope::clientGrantRevision);
    }

    @Test
    void legacyConstructorPreservesEveryPlatformVersion() {
        UserAdmissionScope scope = new UserAdmissionScope(BUSINESS_SCOPE, USER_ID, AUTH_EPOCH,
                11, 12, 13);

        assertEquals(11, scope.tenantSessionEpoch());
        assertEquals(12, scope.membershipRevision());
        assertEquals(13, scope.clientGrantRevision());
        assertEquals(new UserAdmissionScope.PlatformVersions(11, 12, 13),
                scope.platformVersions().orElseThrow());
    }

    @Test
    void rejectsMissingScopeOrPlatformVersionContainer() {
        assertThrows(NullPointerException.class,
                () -> UserAdmissionScope.basic(null, USER_ID, AUTH_EPOCH));
        assertThrows(NullPointerException.class,
                () -> new UserAdmissionScope(BUSINESS_SCOPE, USER_ID, AUTH_EPOCH, null));
    }

    @Test
    void rejectsInvalidUserIdAndAuthEpochForBasicAndPlatformScopes() {
        assertThrows(IllegalArgumentException.class,
                () -> UserAdmissionScope.basic(BUSINESS_SCOPE, 0, AUTH_EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> UserAdmissionScope.basic(BUSINESS_SCOPE, -1, AUTH_EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> UserAdmissionScope.basic(BUSINESS_SCOPE, USER_ID, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope(BUSINESS_SCOPE, 0, AUTH_EPOCH, 1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope(BUSINESS_SCOPE, USER_ID, -1, 1, 2, 3));
    }

    @Test
    void rejectsEachNegativePlatformVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope.PlatformVersions(-1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope.PlatformVersions(1, -1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope.PlatformVersions(1, 2, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope(BUSINESS_SCOPE, USER_ID, AUTH_EPOCH, -1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope(BUSINESS_SCOPE, USER_ID, AUTH_EPOCH, 1, -1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new UserAdmissionScope(BUSINESS_SCOPE, USER_ID, AUTH_EPOCH, 1, 2, -1));
    }

    @Test
    void canonicalConstructorAcceptsValidatedPlatformVersions() {
        UserAdmissionScope.PlatformVersions versions =
                new UserAdmissionScope.PlatformVersions(21, 22, 23);
        UserAdmissionScope scope = new UserAdmissionScope(
                BUSINESS_SCOPE, USER_ID, AUTH_EPOCH, Optional.of(versions));

        assertEquals(Optional.of(versions), scope.platformVersions());
        assertEquals(21, scope.tenantSessionEpoch());
        assertEquals(22, scope.membershipRevision());
        assertEquals(23, scope.clientGrantRevision());
    }
}
