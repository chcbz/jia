package cn.jia.economy.config;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the explicit preview scopes allowed to receive platform seed products. */
public final class EconomySkillSeedGate {
    private static final int MAX_SCOPE_BYTES = 50;

    private final boolean enabled;
    private final List<Scope> allowedScopes;

    public EconomySkillSeedGate(EconomySkillSeedProperties properties) {
        if (properties == null) throw invalidConfiguration();
        LinkedHashSet<Scope> validated = new LinkedHashSet<>();
        for (EconomySkillSeedProperties.AllowedScope configured : properties.allowedScopes()) {
            if (configured == null
                    || !validExact(configured.tenantId())
                    || !validExact(configured.clientId())
                    || !validated.add(new Scope(configured.tenantId(), configured.clientId()))) {
                throw invalidConfiguration();
            }
        }
        if (properties.enabled() && validated.isEmpty()) throw invalidConfiguration();
        enabled = properties.enabled();
        allowedScopes = List.copyOf(validated);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Scope> allowedScopes() {
        return allowedScopes;
    }

    public boolean allows(String tenantId, String clientId) {
        return enabled && allowedScopes.contains(new Scope(tenantId, clientId));
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
        return new IllegalStateException("Invalid economy.skill.seed configuration");
    }

    public record Scope(String tenantId, String clientId) {
    }
}
