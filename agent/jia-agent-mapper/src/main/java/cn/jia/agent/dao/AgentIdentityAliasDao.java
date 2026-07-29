package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.core.dao.IBaseDao;

public interface AgentIdentityAliasDao extends IBaseDao<AgentIdentityAliasEntity> {
    AgentIdentityAliasEntity findExactActiveLegacyAlias(String tenantId, String clientId,
            String ownerJiacn, String aliasValue);

    AgentIdentityAliasEntity findExactLegacyAlias(String tenantId, String clientId,
            String ownerJiacn, String aliasValue);
}
