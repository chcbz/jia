package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskWorkspaceDTO;

import java.util.Objects;

public interface AgentTaskWorkspaceService {
    AgentTaskWorkspaceDTO snapshot(
            String tenantId, String clientId, String taskId, String actorAgentId);

    /**
     * Reuses the C04 browser identity/task/member gate without allocating a snapshot.
     * Implementations must return only after the short read-only authorization transaction ends.
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
