package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemResultCommitDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitViewDTO;

/**
 * Atomic B04+B06 result boundary. The caller must carry the authenticated task-owner scope
 * through lease validation, artifact publication, work-item CAS and event append.
 */
public interface AgentWorkItemResultCommitService {
    AgentWorkItemResultCommitViewDTO commitResult(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String actorAgentId, AgentWorkItemResultCommitDTO command);
}
