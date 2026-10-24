package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/** Immutable dispatch ACL bound only when M3 Rabbit dispatch is enabled. */
@ConfigurationProperties(prefix = "agent.rabbit-dispatch")
public record AgentRabbitDispatchScopeProperties(
        List<AllowedScope> allowedScopes,
        List<AllowedWorkerScope> allowedWorkerScopes) {
    @ConstructorBinding
    public AgentRabbitDispatchScopeProperties {
        allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
        allowedWorkerScopes = allowedWorkerScopes == null
                ? List.of() : List.copyOf(allowedWorkerScopes);
    }

    /** Legacy source compatibility for tenant/client dispatch ACLs. */
    public AgentRabbitDispatchScopeProperties(List<AllowedScope> allowedScopes) {
        this(allowedScopes, List.of());
    }

    public record AllowedScope(String tenantId, String clientId) {
    }

    public record AllowedWorkerScope(
            String tenantId, String dataDomainId, String executionPoolId) {
    }
}
