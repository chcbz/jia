package cn.jia.economy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/** Explicit, default-off platform skill seed scope. */
@ConfigurationProperties(prefix = "economy.skill.seed")
public record EconomySkillSeedProperties(boolean enabled, List<AllowedScope> allowedScopes) {
    @ConstructorBinding
    public EconomySkillSeedProperties {
        allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
    }

    public record AllowedScope(String tenantId, String clientId) {
    }
}
