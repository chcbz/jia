package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Immutable startup-only configuration for the C05F workspace and event gate. */
@ConfigurationProperties(prefix = "agent.task-events")
public record AgentTaskEventsProperties(
        boolean enabled,
        List<AllowedScope> allowedScopes) {

    public AgentTaskEventsProperties {
        allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
    }

    public record AllowedScope(String tenantId, String clientId) {
    }
}
