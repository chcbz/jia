package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskRequestDTO;
import cn.jia.agent.entity.AgentTaskRequestEntity;

import java.util.List;

public interface AgentTaskRequestDao {
    int insert(String tenantId, String clientId, AgentTaskRequestDTO request);

    AgentTaskRequestEntity findByRequestId(String tenantId, String clientId, String requestId);

    List<AgentTaskRequestEntity> listByTask(
            String tenantId, String clientId, String taskId, String status, int limit);

    List<AgentTaskRequestEntity> listByTarget(String tenantId, String clientId,
            String targetType, String targetId, String status, int limit);

    int updateByVersion(String tenantId, String clientId, String requestId,
            long expectedVersion, AgentTaskRequestDTO request);
}
