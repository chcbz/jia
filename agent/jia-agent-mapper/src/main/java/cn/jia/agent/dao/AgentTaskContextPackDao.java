package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskContextPackTaskSourceRow;

public interface AgentTaskContextPackDao {
    AgentTaskContextPackTaskSourceRow findTaskDescription(
            String tenantId, String clientId, String taskId);
}
