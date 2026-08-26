package cn.jia.agent.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exact immutable tenant/client allowlist shared by workspace and task-event endpoints. */
public final class AgentTaskEventsGate {
    private static final int MAX_SCOPE_ID_CODE_POINTS = 50;

    private final boolean enabled;
    private final Set<Scope> allowedScopes;

    public AgentTaskEventsGate(AgentTaskEventsProperties properties) {
        if (properties == null) {
            throw invalidConfiguration();
        }
        LinkedHashSet<Scope> validated = new LinkedHashSet<>();
        for (AgentTaskEventsProperties.AllowedScope configured : properties.allowedScopes()) {
            if (configured == null
                    || !validExact(configured.tenantId())
                    || !validExact(configured.clientId())) {
                throw invalidConfiguration();
            }
            Scope scope = new Scope(configured.tenantId(), configured.clientId());
            if (!validated.add(scope)) {
                throw invalidConfiguration();
            }
        }
        if (properties.enabled() && validated.isEmpty()) {
            throw invalidConfiguration();
        }
        enabled = properties.enabled();
        allowedScopes = Collections.unmodifiableSet(validated);
    }

    public boolean allows(String tenantId, String clientId) {
        return enabled && allowedScopes.contains(new Scope(tenantId, clientId));
    }

    private static boolean validExact(String value) {
        return value != null && !value.contains("*") && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= MAX_SCOPE_ID_CODE_POINTS
                && !value.codePoints().allMatch(AgentTaskEventsGate::isPadding)
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Invalid agent.task-events configuration");
    }

    private record Scope(String tenantId, String clientId) {
    }
}
