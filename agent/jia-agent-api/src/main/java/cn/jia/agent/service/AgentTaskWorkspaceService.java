package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskWorkspaceDTO;

public interface AgentTaskWorkspaceService {
    AgentTaskWorkspaceDTO snapshot(
            String tenantId, String clientId, String ownerJiacn, String taskId, String actorAgentId);
}
