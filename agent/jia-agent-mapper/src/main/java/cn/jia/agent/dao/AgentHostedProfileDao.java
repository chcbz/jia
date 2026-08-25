package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.core.dao.IBaseDao;

public interface AgentHostedProfileDao extends IBaseDao<AgentHostedProfileEntity> {
    AgentHostedProfileEntity findExact(String tenantId, String clientId, String ownerJiacn, long bindingId);
    AgentHostedProfileEntity findExactForUpdate(String tenantId, String clientId, String ownerJiacn, long bindingId);
    int transition(long id, String expectedState, long expectedGeneration,
            String nextState, long nextGeneration, boolean desiredEnabled);
    int markRepair(long id, String resumeState, String lastError);
}
