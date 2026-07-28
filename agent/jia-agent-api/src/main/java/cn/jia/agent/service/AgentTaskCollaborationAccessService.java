package cn.jia.agent.service;

import cn.jia.agent.access.AgentTaskAccessLevel;

/**
 * Fail-closed access projection for collaboration services owned by other modules.
 */
public interface AgentTaskCollaborationAccessService {
    AgentTaskAccessLevel resolveMemberAccess(
            String tenantId, String clientId, String taskId, String agentId);

    AgentTaskAccessLevel resolveMemberAccessForUpdate(
            String tenantId, String clientId, String taskId, String agentId);
}
