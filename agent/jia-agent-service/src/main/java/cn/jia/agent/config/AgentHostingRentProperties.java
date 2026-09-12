package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * Startup-only preview hosting-rent descriptor. The approved V1 values are defaults, while
 * charging remains disabled unless {@code enabled} is explicitly set.
 */
@ConfigurationProperties(prefix = "agent.hosting-rent")
public record AgentHostingRentProperties(
        boolean enabled,
        String planVersion,
        String amountMicro,
        String periodSeconds) {
    public static final String PREVIEW_PLAN_VERSION = "1";
    public static final String PREVIEW_AMOUNT_MICRO = "1000000000";
    public static final String PREVIEW_PERIOD_SECONDS = "2592000";

    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");

    public AgentHostingRentProperties {
        planVersion = planVersion == null ? PREVIEW_PLAN_VERSION : planVersion;
        amountMicro = amountMicro == null ? PREVIEW_AMOUNT_MICRO : amountMicro;
        periodSeconds = periodSeconds == null ? PREVIEW_PERIOD_SECONDS : periodSeconds;
    }

    /** A complete descriptor is inert while the explicit charging switch is false. */
    public boolean configured() {
        return enabled && descriptorConfigured();
    }

    public boolean descriptorConfigured() {
        return canonicalPositiveLong(planVersion)
                && canonicalPositiveLong(amountMicro)
                && canonicalPositiveLong(periodSeconds);
    }

    private static boolean canonicalPositiveLong(String value) {
        if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
