package cn.jia.agent.service;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;

import java.util.List;

/** Stable Agent identity runtime boundary. All inputs are scoped and byte-exact. */
public interface AgentIdentityService {
    AgentIdentityRegistryEntity provisionOpaqueIdentity(
            AgentPersonaBindingEntity binding, String auditReason);

    AgentIdentityRegistryEntity requireRegistrationIdentityInScope(
            String tenantId, String clientId, String ownerJiacn, String requestedAgentId);

    AgentIdentityRegistryEntity activateForFirstRegistration(
            AgentIdentityRegistryEntity identity);

    void suspendForBinding(String tenantId, String clientId, String ownerJiacn, long bindingId);

    /** Resolves an active direct canonical identity first, otherwise an exact approved alias. */
    String resolveAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String requestedAgentId);

    String requireCanonicalAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String canonicalAgentId);

    /**
     * Locks active direct canonical identities for a new mutation. Callers must first lock their
     * aggregate root; identities are then locked in byte-exact deterministic order until commit.
     */
    List<String> lockActiveCanonicalAgentIdsInScope(
            String tenantId, String clientId, String ownerJiacn,
            List<String> canonicalAgentIds);

    /**
     * Validates a historical direct canonical reference without requiring current active lifecycle
     * or binding state. PROVISIONED, system, alias text, unknown and cross-scope references fail.
     */
    String requirePersistedCanonicalAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String canonicalAgentId);

    String resolveLegacyAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String legacyAgentId);

    AgentIdentityRegistryEntity requireActiveIdentityForBinding(
            String tenantId, String clientId, String ownerJiacn, long bindingId,
            String expectedCanonicalAgentId);

    AgentPersonaBindingEntity requireActiveBinding(
            AgentIdentityRegistryEntity identity, String acceptedLegacyAlias);
}
