package cn.jia.agent.service;

import cn.jia.agent.config.AgentHostingRentProperties;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Non-charging R00 admission boundary. It never creates a quote or permits server provisioning.
 */
public class HostingRentAdmissionService {
    private final boolean configured;

    public HostingRentAdmissionService(AgentHostingRentProperties properties) {
        this.configured = Objects.requireNonNull(properties, "properties").configured();
    }

    public static HostingRentAdmissionService unconfigured() {
        return new HostingRentAdmissionService(new AgentHostingRentProperties(false, null, null, null));
    }

    /** Must be called before any legacy server bind read, mutation, identity, runtime, or profile operation. */
    public void requireServerBindAvailable() {
        throw unavailable();
    }

    /** Validates the exact authenticated request identity, then rejects because quotes are not implemented. */
    public void requireQuoteAvailable(Principal principal, String personaCode) {
        Objects.requireNonNull(principal, "principal");
        requireExact(principal.actorId(), 100, "actorId");
        requireExact(principal.tenantId(), 50, "tenantId");
        requireExact(principal.clientId(), 50, "clientId");
        requireExact(personaCode, 100, "personaCode");
        throw unavailable();
    }

    private HostingRentAdmissionException unavailable() {
        return new HostingRentAdmissionException(configured
                ? HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_READY
                : HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED);
    }

    private static void requireExact(String value, int maxBytes, String field) {
        if (!validExact(value, maxBytes)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static boolean validExact(String value, int maxBytes) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))) {
            return false;
        }
        return value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
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

    public record Principal(String actorId, String tenantId, String clientId) {
    }
}
