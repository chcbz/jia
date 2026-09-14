package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper;
import cn.jia.common.dao.BaseDaoImpl;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV1InstallationDaoImpl extends BaseDaoImpl<AgentRuntimeV1InstallationMapper, AgentRuntimeV1InstallationEntity>
        implements AgentRuntimeV1InstallationDao {
    @Override public AgentRuntimeV1InstallationEntity lock(String installationId) { return baseMapper.selectByInstallationForUpdate(installationId); }
    @Override public AgentRuntimeV1InstallationEntity findInScope(String tenantId, String clientId, String installationId) { return baseMapper.selectByInstallationInScope(tenantId, clientId, installationId); }
    @Override public AgentRuntimeV1InstallationEntity findActiveByAuthorizationHash(byte[] hash) { return baseMapper.selectActiveByAuthorizationHash(hash); }
    @Override public int activate(AgentRuntimeV1InstallationEntity e, byte[] hash, long now) { return baseMapper.activateEnrollment(e.getId(), e.getVersion(), hash, now); }
    @Override public int heartbeat(AgentRuntimeV1InstallationEntity e, long now) { return baseMapper.heartbeat(e.getId(), e.getVersion(), now); }
    @Override public int revoke(AgentRuntimeV1InstallationEntity e, long now) { return baseMapper.revoke(e.getId(), e.getVersion(), now); }
}
