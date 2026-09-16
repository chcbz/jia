package cn.jia.agent.service;

import cn.jia.agent.access.AgentTaskAccessLevel;

/**
 * Fail-closed access projection for collaboration services owned by other modules.
 *
 * <p>The single tenant is still not a shared-user scope: every lookup requires the
 * authenticated owner explicitly and must carry it into the SQL predicate.</p>
 */
public interface AgentTaskCollaborationAccessService {
    AgentTaskAccessLevel resolveMemberAccess(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId);

    AgentTaskAccessLevel resolveMemberAccessForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId);
}
