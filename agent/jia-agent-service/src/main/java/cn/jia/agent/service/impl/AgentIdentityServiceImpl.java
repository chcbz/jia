package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.service.AgentIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentIdentityServiceImpl implements AgentIdentityService {
    private static final Pattern OPAQUE_ID = Pattern.compile("agt_[0-9a-f]{32}");
    private static final Pattern OPAQUE_SHAPE_CASE_INSENSITIVE =
            Pattern.compile("agt_[0-9a-f]{32}", Pattern.CASE_INSENSITIVE);

    private final AgentIdentityRegistryDao registryDao;
    private final AgentIdentityAliasDao aliasDao;
    private final AgentPersonaBindingDao bindingDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentIdentityRegistryEntity provisionOpaqueIdentity(
            AgentPersonaBindingEntity binding, String auditReason) {
        requireBindingProjection(binding);
        requireExactText(auditReason, "auditReason", 1000);
        if (!OPAQUE_ID.matcher(binding.getAgentId()).matches()) {
            throw forbidden("New bindings require an opaque canonical Agent ID");
        }
        if (binding.getId() == null) {
            throw forbidden("Identity provisioning requires a persisted binding");
        }
        if (binding.getStatus() == null || binding.getStatus() != AgentConstants.BINDING_STATUS_ACTIVE) {
            throw forbidden("Identity provisioning requires an active binding");
        }

        AgentIdentityRegistryEntity existing = registryDao.findExactByBindingInScope(
                binding.getJiacn(), binding.getClientId(), binding.getJiacn(), binding.getId());
        if (existing != null) {
            validateRegistry(existing, binding.getJiacn(), binding.getClientId(), binding.getJiacn(),
                    binding.getAgentId(), true);
            requireRegistrableLifecycle(existing);
            return existing;
        }

        long now = System.currentTimeMillis();
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setCanonicalAgentId(binding.getAgentId());
        identity.setCanonicalType(AgentConstants.IDENTITY_TYPE_OPAQUE);
        identity.setLifecycleStatus(AgentConstants.IDENTITY_STATUS_PROVISIONED);
        identity.setClientId(binding.getClientId());
        identity.setOwnerJiacn(binding.getJiacn());
        identity.setTenantId(binding.getJiacn());
        identity.setBindingId(binding.getId());
        identity.setProvisionedAt(now);
        identity.setAuditReason(auditReason);
        registryDao.insert(identity);
        return identity;
    }

    @Override
    public AgentIdentityRegistryEntity requireRegistrationIdentityInScope(
            String tenantId, String clientId, String ownerJiacn, String requestedAgentId) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireAgentId(requestedAgentId);
        rejectSystem(requestedAgentId);

        AgentIdentityRegistryEntity direct = registryDao.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, requestedAgentId);
        if (direct != null) {
            validateRegistry(direct, tenantId, clientId, ownerJiacn, requestedAgentId, true);
            requireRegistrableLifecycle(direct);
            requireActiveBinding(direct, null);
            return direct;
        }
        if (OPAQUE_SHAPE_CASE_INSENSITIVE.matcher(requestedAgentId).matches()) {
            throw forbidden("Opaque-shaped Agent ID must resolve directly and byte-exactly");
        }

        AgentIdentityAliasEntity alias = requireExactActiveAlias(
                tenantId, clientId, ownerJiacn, requestedAgentId);
        AgentIdentityRegistryEntity resolved = registryDao.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, alias.getCanonicalAgentId());
        if (resolved == null || !Objects.equals(alias.getRegistryId(), resolved.getId())) {
            throw forbidden("Legacy Agent alias target is missing or inconsistent");
        }
        validateRegistry(resolved, tenantId, clientId, ownerJiacn,
                alias.getCanonicalAgentId(), true);
        requireRegistrableLifecycle(resolved);
        requireActiveBinding(resolved, requestedAgentId);
        return resolved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentIdentityRegistryEntity activateForFirstRegistration(
            AgentIdentityRegistryEntity identity) {
        if (identity == null || identity.getId() == null) {
            throw forbidden("Agent identity is missing");
        }
        AgentIdentityRegistryEntity current = registryDao.findExactByCanonicalInScope(
                identity.getTenantId(), identity.getClientId(), identity.getOwnerJiacn(),
                identity.getCanonicalAgentId());
        if (current == null
                || !Objects.equals(current.getId(), identity.getId())
                || !Objects.equals(current.getBindingId(), identity.getBindingId())) {
            throw forbidden("Agent identity changed before registration activation");
        }
        validateRegistry(current, identity.getTenantId(), identity.getClientId(),
                identity.getOwnerJiacn(), identity.getCanonicalAgentId(), true);
        identity = current;
        requireBinding(identity, null, true);
        if (AgentConstants.IDENTITY_STATUS_ACTIVE.equals(identity.getLifecycleStatus())) {
            return identity;
        }
        if (!AgentConstants.IDENTITY_STATUS_PROVISIONED.equals(identity.getLifecycleStatus())) {
            throw forbidden("Agent identity lifecycle does not allow registration");
        }
        long now = System.currentTimeMillis();
        if (registryDao.activateProvisioned(identity.getId(), now) == 1) {
            identity.setLifecycleStatus(AgentConstants.IDENTITY_STATUS_ACTIVE);
            if (identity.getActivatedAt() == null) {
                identity.setActivatedAt(now);
            }
            return identity;
        }
        AgentIdentityRegistryEntity refreshed = registryDao.findExactByCanonicalInScope(
                identity.getTenantId(), identity.getClientId(), identity.getOwnerJiacn(),
                identity.getCanonicalAgentId());
        if (refreshed != null && AgentConstants.IDENTITY_STATUS_ACTIVE.equals(refreshed.getLifecycleStatus())) {
            validateRegistry(refreshed, identity.getTenantId(), identity.getClientId(),
                    identity.getOwnerJiacn(), identity.getCanonicalAgentId(), true);
            return refreshed;
        }
        throw forbidden("Agent identity activation lost a lifecycle race");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void suspendForBinding(String tenantId, String clientId, String ownerJiacn, long bindingId) {
        requireScope(tenantId, clientId, ownerJiacn);
        AgentIdentityRegistryEntity identity = registryDao.findExactByBindingInScope(
                tenantId, clientId, ownerJiacn, bindingId);
        if (identity == null) {
            throw forbidden("Agent identity registry is missing for binding");
        }
        validateRegistry(identity, tenantId, clientId, ownerJiacn, identity.getCanonicalAgentId(), true);
        if (AgentConstants.IDENTITY_STATUS_SUSPENDED.equals(identity.getLifecycleStatus())) {
            return;
        }
        if (registryDao.suspendUsable(identity.getId(), System.currentTimeMillis()) != 1) {
            throw forbidden("Agent identity lifecycle does not allow suspension");
        }
    }

    @Override
    public String resolveAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String requestedAgentId) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireAgentId(requestedAgentId);
        rejectSystem(requestedAgentId);

        AgentIdentityRegistryEntity direct = registryDao.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, requestedAgentId);
        if (direct != null) {
            validateRegistry(direct, tenantId, clientId, ownerJiacn, requestedAgentId, true);
            requireActiveLifecycle(direct);
            requireActiveBinding(direct, null);
            return direct.getCanonicalAgentId();
        }
        if (OPAQUE_SHAPE_CASE_INSENSITIVE.matcher(requestedAgentId).matches()) {
            throw forbidden("Opaque-shaped Agent ID must resolve directly and byte-exactly");
        }

        AgentIdentityAliasEntity alias = requireExactActiveAlias(
                tenantId, clientId, ownerJiacn, requestedAgentId);
        AgentIdentityRegistryEntity resolved = registryDao.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, alias.getCanonicalAgentId());
        if (resolved == null || !Objects.equals(alias.getRegistryId(), resolved.getId())) {
            throw forbidden("Legacy Agent alias target is missing or inconsistent");
        }
        validateRegistry(resolved, tenantId, clientId, ownerJiacn,
                alias.getCanonicalAgentId(), true);
        requireActiveLifecycle(resolved);
        requireActiveBinding(resolved, requestedAgentId);
        return resolved.getCanonicalAgentId();
    }

    @Override
    public String requireCanonicalAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String canonicalAgentId) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireAgentId(canonicalAgentId);
        rejectSystem(canonicalAgentId);
        AgentIdentityRegistryEntity identity = registryDao.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, canonicalAgentId);
        if (identity == null) {
            throw forbidden("Canonical Agent identity is not registered in this scope");
        }
        validateRegistry(identity, tenantId, clientId, ownerJiacn, canonicalAgentId, true);
        requireActiveLifecycle(identity);
        requireActiveBinding(identity, null);
        return identity.getCanonicalAgentId();
    }

    @Override
    public String resolveLegacyAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String legacyAgentId) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireAgentId(legacyAgentId);
        rejectSystem(legacyAgentId);
        AgentIdentityAliasEntity alias = requireExactActiveAlias(
                tenantId, clientId, ownerJiacn, legacyAgentId);
        AgentIdentityRegistryEntity identity = registryDao.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, alias.getCanonicalAgentId());
        if (identity == null || !Objects.equals(alias.getRegistryId(), identity.getId())) {
            throw forbidden("Legacy Agent alias target is missing or inconsistent");
        }
        validateRegistry(identity, tenantId, clientId, ownerJiacn,
                alias.getCanonicalAgentId(), true);
        requireActiveLifecycle(identity);
        requireActiveBinding(identity, legacyAgentId);
        return identity.getCanonicalAgentId();
    }

    @Override
    public AgentIdentityRegistryEntity requireActiveIdentityForBinding(
            String tenantId, String clientId, String ownerJiacn, long bindingId,
            String expectedCanonicalAgentId) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireAgentId(expectedCanonicalAgentId);
        AgentIdentityRegistryEntity identity = registryDao.findExactByBindingInScope(
                tenantId, clientId, ownerJiacn, bindingId);
        if (identity == null) {
            throw forbidden("Agent identity registry is missing for binding");
        }
        validateRegistry(identity, tenantId, clientId, ownerJiacn,
                expectedCanonicalAgentId, true);
        requireActiveLifecycle(identity);
        requireActiveBinding(identity, null);
        return identity;
    }

    @Override
    public AgentPersonaBindingEntity requireActiveBinding(
            AgentIdentityRegistryEntity identity, String acceptedLegacyAlias) {
        return requireBinding(identity, acceptedLegacyAlias, false);
    }

    private AgentPersonaBindingEntity requireBinding(
            AgentIdentityRegistryEntity identity, String acceptedLegacyAlias, boolean forUpdate) {
        if (identity == null || identity.getBindingId() == null) {
            throw forbidden("External Agent identity must reference a binding");
        }
        AgentPersonaBindingEntity binding = forUpdate
                ? bindingDao.findByIdForUpdate(identity.getBindingId())
                : bindingDao.selectById(identity.getBindingId());
        if (binding == null
                || binding.getStatus() == null
                || binding.getStatus() != AgentConstants.BINDING_STATUS_ACTIVE
                || !Objects.equals(identity.getClientId(), binding.getClientId())
                || !Objects.equals(identity.getOwnerJiacn(), binding.getJiacn())
                || (binding.getTenantId() != null
                    && !Objects.equals(identity.getTenantId(), binding.getTenantId()))) {
            throw forbidden("Agent binding is inactive, missing, or outside identity scope");
        }
        if (Objects.equals(identity.getCanonicalAgentId(), binding.getAgentId())) {
            return binding;
        }
        if (acceptedLegacyAlias != null && !Objects.equals(acceptedLegacyAlias, binding.getAgentId())) {
            throw forbidden("Accepted legacy alias does not match the audited binding Agent ID");
        }
        AgentIdentityAliasEntity bindingAlias = aliasDao.findExactActiveLegacyAlias(
                identity.getTenantId(), identity.getClientId(), identity.getOwnerJiacn(),
                binding.getAgentId());
        if (bindingAlias == null
                || !Objects.equals(bindingAlias.getRegistryId(), identity.getId())
                || !Objects.equals(bindingAlias.getCanonicalAgentId(), identity.getCanonicalAgentId())) {
            throw forbidden("Binding Agent ID does not match canonical identity or an approved alias");
        }
        return binding;
    }

    private AgentIdentityAliasEntity requireExactActiveAlias(
            String tenantId, String clientId, String ownerJiacn, String legacyAgentId) {
        AgentIdentityAliasEntity alias = aliasDao.findExactActiveLegacyAlias(
                tenantId, clientId, ownerJiacn, legacyAgentId);
        if (alias == null) {
            throw forbidden("Legacy Agent ID is not explicitly approved in this scope");
        }
        if (!AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID.equals(alias.getAliasType())
                || !AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE.equals(alias.getAliasStatus())
                || alias.getValidTo() != null
                || !Objects.equals(tenantId, alias.getTenantId())
                || !Objects.equals(clientId, alias.getClientId())
                || !Objects.equals(ownerJiacn, alias.getOwnerJiacn())
                || !Objects.equals(legacyAgentId, alias.getAliasValue())) {
            throw forbidden("Legacy Agent alias is non-canonical or outside scope");
        }
        requireAgentId(alias.getCanonicalAgentId());
        return alias;
    }

    private void validateRegistry(AgentIdentityRegistryEntity identity,
            String tenantId, String clientId, String ownerJiacn,
            String expectedCanonicalAgentId, boolean externalOnly) {
        if (!Objects.equals(tenantId, identity.getTenantId())
                || !Objects.equals(clientId, identity.getClientId())
                || !Objects.equals(ownerJiacn, identity.getOwnerJiacn())
                || !Objects.equals(expectedCanonicalAgentId, identity.getCanonicalAgentId())) {
            throw forbidden("Agent identity scope or ID is not byte-exact");
        }
        requireAgentId(identity.getCanonicalAgentId());
        String type = identity.getCanonicalType();
        if (AgentConstants.IDENTITY_TYPE_OPAQUE.equals(type)) {
            if (!OPAQUE_ID.matcher(identity.getCanonicalAgentId()).matches()) {
                throw forbidden("OPAQUE identity has a non-canonical Agent ID");
            }
        } else if (AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL.equals(type)) {
            if (OPAQUE_ID.matcher(identity.getCanonicalAgentId()).matches()
                    || AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(identity.getCanonicalAgentId())) {
                throw forbidden("LEGACY_CANONICAL identity classification is invalid");
            }
        } else if (AgentConstants.IDENTITY_TYPE_SYSTEM.equals(type)) {
            if (!AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(identity.getCanonicalAgentId())
                    || externalOnly) {
                throw forbidden("System identity cannot be used by external Agents");
            }
        } else {
            throw forbidden("Agent identity type is non-canonical");
        }
    }

    private void requireRegistrableLifecycle(AgentIdentityRegistryEntity identity) {
        if (!AgentConstants.IDENTITY_STATUS_PROVISIONED.equals(identity.getLifecycleStatus())
                && !AgentConstants.IDENTITY_STATUS_ACTIVE.equals(identity.getLifecycleStatus())) {
            throw forbidden("Agent identity lifecycle does not allow registration");
        }
    }

    private void requireActiveLifecycle(AgentIdentityRegistryEntity identity) {
        if (!AgentConstants.IDENTITY_STATUS_ACTIVE.equals(identity.getLifecycleStatus())) {
            throw forbidden("Agent identity is not active");
        }
    }

    private void requireBindingProjection(AgentPersonaBindingEntity binding) {
        if (binding == null) {
            throw forbidden("Agent binding is required");
        }
        requireScope(binding.getJiacn(), binding.getClientId(), binding.getJiacn());
        requireAgentId(binding.getAgentId());
        if (binding.getTenantId() != null && !Objects.equals(binding.getTenantId(), binding.getJiacn())) {
            throw forbidden("Agent binding tenant does not match owner scope");
        }
    }

    private void requireScope(String tenantId, String clientId, String ownerJiacn) {
        requireExactText(tenantId, "tenantId", 50);
        requireExactText(clientId, "clientId", 50);
        requireExactText(ownerJiacn, "ownerJiacn", 50);
        if (!tenantId.equals(ownerJiacn)) {
            throw forbidden("Agent identity tenant must equal owner scope");
        }
    }

    private void requireAgentId(String agentId) {
        requireExactText(agentId, "agentId", 100);
    }

    private void requireExactText(String value, String name, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw forbidden(name + " must be nonblank, unpadded, and free of control characters");
        }
    }

    private void rejectSystem(String agentId) {
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agentId)) {
            throw forbidden("System agent cannot be registered or resolved externally");
        }
    }

    private AgentServiceImpl.AgentBizException forbidden(String message) {
        return new AgentServiceImpl.AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, message);
    }
}
