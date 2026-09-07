package cn.jia.chat.archive.config;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class ArchiveReaderAccessPolicy {
    public static final int MAX_SCOPE_UTF8_BYTES = 50;

    private final boolean enabled;
    private final Set<Scope> allowedScopes;

    private ArchiveReaderAccessPolicy(boolean enabled, Set<Scope> allowedScopes) {
        this.enabled = enabled;
        this.allowedScopes = Set.copyOf(allowedScopes);
    }

    public static ArchiveReaderAccessPolicy from(ArchiveReaderProperties properties) {
        Objects.requireNonNull(properties, "properties");
        Set<Scope> scopes = new LinkedHashSet<>();
        for (ArchiveReaderProperties.AllowedScope configured : properties.getAllowedScopes()) {
            if (configured == null) {
                throw invalid("null scope");
            }
            String tenantId = requireScopeValue(configured.getTenantId(), "tenant-id");
            String clientId = requireScopeValue(configured.getClientId(), "client-id");
            if (!scopes.add(new Scope(tenantId, clientId))) {
                throw invalid("duplicate scope");
            }
        }
        if (properties.isEnabled() && scopes.isEmpty()) {
            throw invalid("enabled configuration requires at least one allowed scope");
        }
        return new ArchiveReaderAccessPolicy(properties.isEnabled(), scopes);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean allows(String tenantId, String clientId) {
        return enabled && validRequestClaim(tenantId) && validRequestClaim(clientId)
                && allowedScopes.contains(new Scope(tenantId, clientId));
    }

    public static boolean validRequestClaim(String value) {
        return validValue(value);
    }

    private static String requireScopeValue(String value, String name) {
        if (!validValue(value)) {
            throw invalid("invalid " + name);
        }
        return value;
    }

    private static boolean validValue(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && !value.contains("*")
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_SCOPE_UTF8_BYTES
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Invalid archive.reader.allowed-scopes: " + reason);
    }

    private record Scope(String tenantId, String clientId) {
    }
}
