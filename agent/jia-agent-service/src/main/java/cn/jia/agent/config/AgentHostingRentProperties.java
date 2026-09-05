package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/** Startup-only hosting-rent plan. No values are enabled or defaulted by R00. */
@ConfigurationProperties(prefix = "agent.hosting-rent")
public record AgentHostingRentProperties(
        boolean enabled,
        String planVersion,
        String amountMicro,
        String periodSeconds) {
    private static final Pattern PLAN_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");

    /** A complete plan remains unavailable until the paid coordinator is implemented. */
    public boolean configured() {
        return enabled
                && planVersion != null && PLAN_VERSION.matcher(planVersion).matches()
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
