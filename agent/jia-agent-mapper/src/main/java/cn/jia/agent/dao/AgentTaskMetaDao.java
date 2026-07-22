package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentTaskMetaDao extends IBaseDao<AgentTaskMetaEntity> {
    AgentTaskMetaEntity findByTaskId(String taskId);

    AgentTaskMetaEntity findByTaskId(String tenantId, String clientId, String taskId);

    int updateStatusByVersion(String tenantId, String clientId, String taskId,
            long expectedVersion, String rewardStatus, Long startedAt, Long completedAt,
            String failureReason);

    List<AgentTaskMetaEntity> findByAgentId(String agentId);

    List<AgentTaskMetaEntity> search(String status, String ability);
}
