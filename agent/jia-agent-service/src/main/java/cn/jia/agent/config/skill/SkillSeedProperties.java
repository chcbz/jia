package cn.jia.agent.config.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/** Explicit, default-off platform skill seed scope. */
@ConfigurationProperties(prefix = "economy.skill.seed")
public record SkillSeedProperties(boolean enabled, List<AllowedScope> allowedScopes) {
    @ConstructorBinding
    public SkillSeedProperties {
        allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
    }

    public record AllowedScope(String tenantId, String clientId) {
    }
}
