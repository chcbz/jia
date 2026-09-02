package cn.jia.economy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "economy.preview")
public record EconomyPreviewProperties(boolean enabled, List<AllowedScope> allowedScopes) {
    public EconomyPreviewProperties {
        allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
    }

    public record AllowedScope(String tenantId, String clientId) {
    }
}
