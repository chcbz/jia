package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskContextPackDTO;

public interface AgentTaskContextPackService {
    AgentTaskContextPackDTO generate(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String expectedVersion);
}
