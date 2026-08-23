package cn.jia.agent.service;

import java.util.Objects;

/** Service-module-only C05 browser authorization boundary. */
public interface AgentTaskEventAccessService {

    /**
     * Authorize one canonical task member and return only after the short read transaction ends.
     */
    AuthorizedSubject authorize(
            String tenantId, String clientId, String taskId, String actorAgentId);

    /** Immutable browser authorization result safe to retain for one SSE connection. */
    record AuthorizedSubject(
            String tenantId,
            String clientId,
            String taskId,
            String actorAgentId,
            String actorRole,
            String coordinatorAgentId) {
        public AuthorizedSubject {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actorAgentId, "actorAgentId");
            Objects.requireNonNull(actorRole, "actorRole");
        }

        public boolean coordinatorAccess() {
            return "coordinator".equals(actorRole)
                    || actorAgentId.equals(coordinatorAgentId);
        }

        public boolean reviewerAccess() {
            return "reviewer".equals(actorRole);
        }
    }
}
