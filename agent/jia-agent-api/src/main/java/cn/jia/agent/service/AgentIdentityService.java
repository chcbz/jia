package cn.jia.agent.service;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;

/** Stable Agent identity runtime boundary. All inputs are scoped and byte-exact. */
public interface AgentIdentityService {
    AgentIdentityRegistryEntity provisionOpaqueIdentity(
            AgentPersonaBindingEntity binding, String auditReason);

    AgentIdentityRegistryEntity requireRegistrationIdentityInScope(
            String tenantId, String clientId, String ownerJiacn, String requestedAgentId);

    AgentIdentityRegistryEntity activateForFirstRegistration(
            AgentIdentityRegistryEntity identity);

    void suspendForBinding(String tenantId, String clientId, String ownerJiacn, long bindingId);

    String requireCanonicalAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String canonicalAgentId);

    String resolveLegacyAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String legacyAgentId);

    AgentIdentityRegistryEntity requireActiveIdentityForBinding(
            String tenantId, String clientId, String ownerJiacn, long bindingId,
            String expectedCanonicalAgentId);

    AgentPersonaBindingEntity requireActiveBinding(
            AgentIdentityRegistryEntity identity, String acceptedLegacyAlias);
}
