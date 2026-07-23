package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemResultCommitDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitViewDTO;

/**
 * Atomic B04+B06 result boundary. Implementations must call B04 lease validation and then use the
 * returned exact agent/token/status/leaseUntil/version snapshot in the same transaction's work-item
 * result CAS. An ordinary artifact publish is not an authoritative result commit.
 */
public interface AgentWorkItemResultCommitService {
    AgentWorkItemResultCommitViewDTO commitResult(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentWorkItemResultCommitDTO command);
}
