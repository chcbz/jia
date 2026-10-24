package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentRuntimeDao extends IBaseDao<AgentRuntimeEntity> {
    AgentRuntimeEntity findByAgentId(String agentId);

    AgentRuntimeEntity findInScope(String tenantId, String clientId, String agentId);

    AgentRuntimeEntity lockInScope(String tenantId, String clientId, String agentId);

    AgentRuntimeEntity findByAgentIdForUpdate(String agentId);

    List<AgentRuntimeEntity> findByStatusAndAbility(String status, String ability);

    List<AgentRuntimeEntity> findRosterByOwner(
            String tenantId, String clientId, String jiacn, String status, String ability);

    default List<AgentRuntimeEntity> findRosterByOwner(
            String clientId, String jiacn, String status, String ability) {
        return findRosterByOwner("0", clientId, jiacn, status, ability);
    }

    List<AgentRuntimeEntity> findCandidateRosterByOwner(
            String tenantId, String clientId, String jiacn);

    default List<AgentRuntimeEntity> findCandidateRosterByOwner(String clientId, String jiacn) {
        return findCandidateRosterByOwner("0", clientId, jiacn);
    }

    int clearBindingAfterUnbind(long runtimeId, String agentId, long bindingId,
            String clientId, String ownerJiacn, long detachedAt);

    List<AgentRuntimeEntity> findMapVisible(String tenantId, String clientId);

    default List<AgentRuntimeEntity> findMapVisible(String clientId) {
        return findMapVisible("0", clientId);
    }

    List<AgentRuntimeEntity> findHeartbeatTimedOut(long cutoffTime);
}
