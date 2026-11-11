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
        String amountWen,
        String periodSeconds, String selfHostedPlanVersion, String selfHostedAmountWen) {
    public static final String MANAGED_PLAN_ID = "agent-hosting-preview";
    public static final String SELF_HOSTED_PLAN_ID = "agent-self-hosted";
    public static String modeForPlan(String planId) {
        if (MANAGED_PLAN_ID.equals(planId)) return "server";
        if (SELF_HOSTED_PLAN_ID.equals(planId)) return "local";
        throw new IllegalArgumentException("Unknown hosting plan");
    }
    public static final String PREVIEW_PLAN_VERSION = "1";
    public static final String PREVIEW_AMOUNT_WEN = ""; // Explicit WEN price required; no legacy conversion.
    public static final String PREVIEW_PERIOD_SECONDS = "2592000";

    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");

    public AgentHostingRentProperties {
        planVersion = planVersion == null ? PREVIEW_PLAN_VERSION : planVersion;
        amountWen = amountWen == null ? PREVIEW_AMOUNT_WEN : amountWen;
        periodSeconds = periodSeconds == null ? PREVIEW_PERIOD_SECONDS : periodSeconds;
        selfHostedPlanVersion = selfHostedPlanVersion == null ? "1" : selfHostedPlanVersion;
        selfHostedAmountWen = selfHostedAmountWen == null ? "50" : selfHostedAmountWen;
    }

    /** A complete descriptor is inert while the explicit charging switch is false. */
    public boolean configured() {
        return enabled && descriptorConfigured();
    }

    public boolean descriptorConfigured() {
        return canonicalPositiveLong(planVersion)
                && canonicalPositiveLong(amountWen)
                && canonicalPositiveLong(periodSeconds)
                && canonicalPositiveLong(selfHostedPlanVersion) && canonicalPositiveLong(selfHostedAmountWen);
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
