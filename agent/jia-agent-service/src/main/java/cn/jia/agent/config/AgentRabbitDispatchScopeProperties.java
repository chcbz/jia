package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Immutable dispatch ACL bound only when M3 Rabbit dispatch is enabled. */
@ConfigurationProperties(prefix = "agent.rabbit-dispatch")
public record AgentRabbitDispatchScopeProperties(List<AllowedScope> allowedScopes) {
    public AgentRabbitDispatchScopeProperties {
        allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
    }

    public record AllowedScope(String tenantId, String clientId) {
    }
}
