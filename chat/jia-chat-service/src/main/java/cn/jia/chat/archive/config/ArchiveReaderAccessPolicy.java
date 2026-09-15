package cn.jia.chat.archive.config;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Archive reading is available to every authenticated caller while the feature is enabled.
 *
 * <p>Per-user data isolation is enforced by the server-derived owner scope used by the
 * {@code /archive/v1/me/**} services. It must not be implemented as a reader-entry allowlist:
 * doing so turns a rollout setting into an accidental per-user denial.</p>
 */
public final class ArchiveReaderAccessPolicy {
    public static final int MAX_SCOPE_UTF8_BYTES = 50;

    private final boolean enabled;

    private ArchiveReaderAccessPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public static ArchiveReaderAccessPolicy from(ArchiveReaderProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return new ArchiveReaderAccessPolicy(properties.isEnabled());
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Allows any valid JWT-derived owner/client pair when the archive feature is enabled.
     * Legacy {@code archive.reader.allowed-scopes[*]} configuration is deliberately not an
     * authorization condition; keeping it during rollout does not reintroduce per-user gating.
     */
    public boolean allows(String tenantId, String clientId) {
        return enabled && validRequestClaim(tenantId) && validRequestClaim(clientId);
    }

    public static boolean validRequestClaim(String value) {
        return validValue(value);
    }

    private static boolean validValue(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && !value.contains("*")
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_SCOPE_UTF8_BYTES
                && value.chars().noneMatch(Character::isISOControl);
    }
}
