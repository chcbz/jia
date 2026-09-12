package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentRuntimeDao extends IBaseDao<AgentRuntimeEntity> {
    AgentRuntimeEntity findByAgentId(String agentId);

    AgentRuntimeEntity findByAgentIdForUpdate(String agentId);

    List<AgentRuntimeEntity> findByStatusAndAbility(String status, String ability);

    List<AgentRuntimeEntity> findRosterByOwner(String clientId, String jiacn, String status, String ability);

    List<AgentRuntimeEntity> findCandidateRosterByOwner(String clientId, String jiacn);

    int clearBindingAfterUnbind(long runtimeId, String agentId, long bindingId,
            String clientId, String ownerJiacn, long detachedAt);

    List<AgentRuntimeEntity> findMapVisible(String clientId);

    List<AgentRuntimeEntity> findHeartbeatTimedOut(long cutoffTime);
}
