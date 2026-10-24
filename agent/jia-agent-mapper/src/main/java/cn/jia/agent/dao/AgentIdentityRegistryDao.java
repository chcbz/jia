package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentIdentityRegistryDao extends IBaseDao<AgentIdentityRegistryEntity> {
    /** Credential bootstrap only: exact tenant/client/canonical key, capped so duplicates fail closed. */
    List<AgentIdentityRegistryEntity> findBootstrapExactByCanonical(
            String tenantId, String clientId, String canonicalAgentId);

    AgentIdentityRegistryEntity findExactByCanonicalInScope(String tenantId, String clientId,
            String ownerJiacn, String canonicalAgentId);

    AgentIdentityRegistryEntity findExactByCanonicalInScopeForUpdate(
            String tenantId, String clientId, String ownerJiacn, String canonicalAgentId);

    AgentIdentityRegistryEntity findExactByBindingInScope(String tenantId, String clientId,
            String ownerJiacn, long bindingId);

    AgentIdentityRegistryEntity findExactByBindingInScopeForUpdate(
            String tenantId, String clientId, String ownerJiacn, long bindingId);

    int activateProvisioned(long id, long activatedAt);

    int suspendUsable(long id, long suspendedAt);
}
