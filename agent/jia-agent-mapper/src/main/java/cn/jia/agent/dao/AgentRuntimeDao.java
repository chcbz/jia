package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentRuntimeDao extends IBaseDao<AgentRuntimeEntity> {
    AgentRuntimeEntity findByAgentId(String agentId);

    List<AgentRuntimeEntity> findByStatusAndAbility(String status, String ability);

    List<AgentRuntimeEntity> findHeartbeatTimedOut(long cutoffTime);
}
