package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;

import java.util.List;

public interface AgentTaskWorkItemDao {
    int insert(String tenantId, String clientId, AgentTaskWorkItemDTO item);

    AgentTaskWorkItemEntity findByWorkItemId(String tenantId, String clientId, String workItemId);

    List<AgentTaskWorkItemEntity> listByTask(
            String tenantId, String clientId, String taskId, String status, int limit);

    List<AgentTaskWorkItemEntity> listByAssignee(
            String tenantId, String clientId, String assigneeAgentId, String status, int limit);

    int updateByVersion(String tenantId, String clientId, String workItemId,
            long expectedVersion, AgentTaskWorkItemDTO item);
}
