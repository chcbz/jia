package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveReaderAccessPolicyTest {
    @Test
    void disabledNeverAuthorizes() {
        ArchiveReaderAccessPolicy policy = ArchiveReaderAccessPolicy.from(new ArchiveReaderProperties());
        assertFalse(policy.enabled());
        assertFalse(policy.allows("tenant-a", "client-a"));
    }

    @Test
    void enabledAllowsEveryValidAuthenticatedOwnerAndClientPair() {
        ArchiveReaderProperties properties = properties(true,
                scope("tenant-a", "client-a"));
        ArchiveReaderAccessPolicy policy = ArchiveReaderAccessPolicy.from(properties);

        assertTrue(policy.allows("tenant-a", "client-a"));
        assertTrue(policy.allows("tenant-b", "client-a"));
        assertTrue(policy.allows("tenant-a", "client-b"));
    }

    @Test
    void legacyAllowlistDoesNotBecomePerUserAuthorization() {
        ArchiveReaderAccessPolicy policy = ArchiveReaderAccessPolicy.from(properties(true,
                scope("tenant-a", "client-a")));

        assertTrue(policy.allows("tenant-b", "client-b"));
    }

    @Test
    void rejectsMalformedOrUnsafeJwtClaims() {
        ArchiveReaderAccessPolicy policy = ArchiveReaderAccessPolicy.from(properties(true));
        for (String invalid : List.of("*", " tenant-a", "tenant-a\n", "", "x".repeat(51))) {
            assertFalse(policy.allows(invalid, "client-a"));
            assertFalse(policy.allows("tenant-a", invalid));
        }
    }

    private ArchiveReaderProperties properties(boolean enabled, ArchiveReaderProperties.AllowedScope... scopes) {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(enabled);
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scopes)));
        return properties;
    }

    private ArchiveReaderProperties.AllowedScope scope(String tenantId, String clientId) {
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId(tenantId);
        scope.setClientId(clientId);
        return scope;
    }
}
