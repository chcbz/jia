package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentTaskMetaDao extends IBaseDao<AgentTaskMetaEntity> {
    AgentTaskMetaEntity findByTaskId(String taskId);

    List<AgentTaskMetaEntity> findByAgentId(String agentId);

    List<AgentTaskMetaEntity> search(String status, String ability);
}
