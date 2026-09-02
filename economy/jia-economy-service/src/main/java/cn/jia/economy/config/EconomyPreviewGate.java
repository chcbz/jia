package cn.jia.economy.config;

import cn.jia.economy.exception.EconomyPostingException;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static cn.jia.economy.exception.EconomyPostingException.Reason.FEATURE_DISABLED;

/** Immutable exact-scope feature gate; absent configuration is disabled. */
public final class EconomyPreviewGate {
    private static final int MAX_SCOPE_BYTES = 50;

    private final boolean enabled;
    private final Set<Scope> allowedScopes;

    public EconomyPreviewGate(EconomyPreviewProperties properties) {
        if (properties == null) throw invalidConfiguration();
        LinkedHashSet<Scope> validated = new LinkedHashSet<>();
        for (EconomyPreviewProperties.AllowedScope configured : properties.allowedScopes()) {
            if (configured == null
                    || !validExact(configured.tenantId())
                    || !validExact(configured.clientId())
                    || !validated.add(new Scope(configured.tenantId(), configured.clientId()))) {
                throw invalidConfiguration();
            }
        }
        if (properties.enabled() && validated.isEmpty()) throw invalidConfiguration();
        enabled = properties.enabled();
        allowedScopes = Collections.unmodifiableSet(validated);
    }

    public boolean allows(String tenantId, String clientId) {
        return enabled && allowedScopes.contains(new Scope(tenantId, clientId));
    }

    public void requireMutationAllowed(String tenantId, String clientId) {
        if (!allows(tenantId, clientId)) {
            throw new EconomyPostingException(FEATURE_DISABLED,
                    "economy preview is disabled for this exact scope");
        }
    }

    private static boolean validExact(String value) {
        if (value == null || value.isEmpty() || value.contains("*")
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_SCOPE_BYTES
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(unit) || Character.isISOControl(unit)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Invalid economy.preview configuration");
    }

    private record Scope(String tenantId, String clientId) {
    }
}
