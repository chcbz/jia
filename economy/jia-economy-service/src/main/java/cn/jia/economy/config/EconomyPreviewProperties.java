package cn.jia.economy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "economy.preview")
public record EconomyPreviewProperties(
        boolean enabled,
        boolean testIssuanceEnabled,
        List<AllowedScope> allowedScopes) {
    public EconomyPreviewProperties {
        allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
    }

    /** Compatibility constructor for the W02 foundation tests and callers. */
    public EconomyPreviewProperties(boolean enabled, List<AllowedScope> allowedScopes) {
        this(enabled, false, allowedScopes);
    }

    public record AllowedScope(String tenantId, String clientId) {
    }
}
