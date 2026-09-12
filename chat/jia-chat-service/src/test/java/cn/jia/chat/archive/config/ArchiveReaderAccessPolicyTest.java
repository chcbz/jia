package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveReaderAccessPolicyTest {
    @Test
    void disabledAllowsNoScopesButNeverAuthorizes() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        ArchiveReaderAccessPolicy policy = ArchiveReaderAccessPolicy.from(properties);
        assertFalse(policy.enabled());
        assertFalse(policy.allows("tenant-a", "client-a"));
    }

    @Test
    void enabledRequiresOneExactImmutableScope() {
        ArchiveReaderProperties properties = properties(true,
                scope("tenant-a", "client-a"), scope("tenant-b", "client-b"));
        ArchiveReaderAccessPolicy policy = ArchiveReaderAccessPolicy.from(properties);
        assertTrue(policy.allows("tenant-a", "client-a"));
        assertFalse(policy.allows("tenant-a", "client-b"));
        properties.getAllowedScopes().getFirst().setTenantId("mutated");
        assertTrue(policy.allows("tenant-a", "client-a"));
        assertFalse(policy.allows("mutated", "client-a"));
    }

    @Test
    void startupRejectsEmptyDuplicateWildcardPaddedControlBlankAndOversizeScopes() {
        assertThrows(IllegalStateException.class, () -> ArchiveReaderAccessPolicy.from(properties(true)));
        assertThrows(IllegalStateException.class, () -> ArchiveReaderAccessPolicy.from(properties(true,
                scope("tenant-a", "client-a"), scope("tenant-a", "client-a"))));
        for (ArchiveReaderProperties.AllowedScope invalid : List.of(
                scope("*", "client-a"), scope(" tenant-a", "client-a"),
                scope("tenant-a", "client-a\n"), scope("", "client-a"),
                scope("x".repeat(51), "client-a"))) {
            assertThrows(IllegalStateException.class,
                    () -> ArchiveReaderAccessPolicy.from(properties(true, invalid)));
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
