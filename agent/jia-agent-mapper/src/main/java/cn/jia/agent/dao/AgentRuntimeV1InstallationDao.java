package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.core.dao.IBaseDao;

public interface AgentRuntimeV1InstallationDao extends IBaseDao<AgentRuntimeV1InstallationEntity> {
    AgentRuntimeV1InstallationEntity lock(String installationId);
    AgentRuntimeV1InstallationEntity findInScope(String tenantId, String clientId, String installationId);
    AgentRuntimeV1InstallationEntity findActiveByAuthorizationHash(byte[] authorizationHash);
    int activate(AgentRuntimeV1InstallationEntity installation, byte[] authorizationHash, long now);
    int heartbeat(AgentRuntimeV1InstallationEntity installation, long now);
    int revoke(AgentRuntimeV1InstallationEntity installation, long now);
}
