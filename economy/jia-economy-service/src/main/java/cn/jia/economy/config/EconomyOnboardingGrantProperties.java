package cn.jia.economy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

/** One audited, campaign-scoped welcome grant per authenticated payer. */
@ConfigurationProperties(prefix = "economy.onboarding-grant")
public record EconomyOnboardingGrantProperties(
        boolean enabled,
        long amountMicro,
        String campaignRef) {
    public EconomyOnboardingGrantProperties {
        if (enabled && (amountMicro <= 0 || !validExact(campaignRef, 100))) {
            throw new IllegalStateException("Invalid economy.onboarding-grant configuration");
        }
    }

    public static EconomyOnboardingGrantProperties disabled() {
        return new EconomyOnboardingGrantProperties(false, 0, null);
    }

    private static boolean validExact(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || Character.isWhitespace(value.codePointAt(0))
                || Character.isWhitespace(value.codePointBefore(value.length()))) return false;
        return value.codePoints().noneMatch(Character::isISOControl);
    }
}
