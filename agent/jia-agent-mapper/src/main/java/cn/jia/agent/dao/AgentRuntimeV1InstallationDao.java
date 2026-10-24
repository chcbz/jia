package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.core.dao.IBaseDao;

public interface AgentRuntimeV1InstallationDao extends IBaseDao<AgentRuntimeV1InstallationEntity> {
    void insertCandidateIfAbsent(AgentRuntimeV1InstallationEntity candidate);
    AgentRuntimeV1InstallationEntity findBootstrap(String installationId);
    AgentRuntimeV1InstallationEntity lock(String installationId);
    AgentRuntimeV1InstallationEntity findInScope(String tenantId, String clientId, String installationId);
    AgentRuntimeV1InstallationEntity findBootstrapActiveByAuthorizationHash(byte[] authorizationHash);
    /** @deprecated Runtime credential entry points must use the explicit bootstrap name. */
    @Deprecated
    default AgentRuntimeV1InstallationEntity findActiveByAuthorizationHash(byte[] authorizationHash) {
        return findBootstrapActiveByAuthorizationHash(authorizationHash);
    }
    int activate(AgentRuntimeV1InstallationEntity installation, byte[] authorizationHash, long now);
    int heartbeat(AgentRuntimeV1InstallationEntity installation, long now);
    int revoke(AgentRuntimeV1InstallationEntity installation, long now);
}
