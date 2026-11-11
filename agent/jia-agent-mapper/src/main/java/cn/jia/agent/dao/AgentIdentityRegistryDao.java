package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.core.dao.IBaseDao;

public interface AgentIdentityRegistryDao extends IBaseDao<AgentIdentityRegistryEntity> {
    AgentIdentityRegistryEntity findExactByCanonicalInScope(String tenantId, String clientId,
            String ownerJiacn, String canonicalAgentId);

    /** Identity provenance for public-instance re-invitation, not an owner read/registration grant. */
    AgentIdentityRegistryEntity findReleasedCanonical(String tenantId, String clientId, String canonicalAgentId);

    /** Historical read grant, never a registration/dispatch authorization. */
    AgentIdentityRegistryEntity findActivatedByCanonicalInScope(String tenantId, String clientId,
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
