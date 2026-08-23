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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentIdentityServiceImpl implements AgentIdentityService {
    private static final Pattern OPAQUE_ID = Pattern.compile("agt_[0-9a-f]{32}");
    private static final Pattern OPAQUE_SHAPE_CASE_INSENSITIVE =
            Pattern.compile("agt_[0-9a-f]{32}", Pattern.CASE_INSENSITIVE);
    private static final Comparator<String> BYTE_EXACT_ORDER =
            AgentIdentityServiceImpl::compareUtf8Unsigned;

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
        if (identity == null || identity.getId() == null || identity.getBindingId() == null) {
            throw forbidden("Agent identity is missing");
        }
        AgentPersonaBindingEntity lockedBinding = bindingDao.findByIdForUpdate(identity.getBindingId());
        AgentIdentityRegistryEntity current = registryDao.findExactByCanonicalInScopeForUpdate(
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
        requireBindingProjection(identity, lockedBinding, null, true);
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
        AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(bindingId);
        requireBindingScope(binding, tenantId, clientId, ownerJiacn, bindingId);
        AgentIdentityRegistryEntity identity = registryDao.findExactByBindingInScopeForUpdate(
                tenantId, clientId, ownerJiacn, bindingId);
        if (identity == null) {
            throw forbidden("Agent identity registry is missing for binding");
        }
        validateRegistry(identity, tenantId, clientId, ownerJiacn,
                identity.getCanonicalAgentId(), true);
        requireBindingProjection(identity, binding, null, false);
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
    @Transactional(rollbackFor = Exception.class)
    public List<String> lockActiveCanonicalAgentIdsInScope(
            String tenantId, String clientId, String ownerJiacn,
            List<String> canonicalAgentIds) {
        requireScope(tenantId, clientId, ownerJiacn);
        if (canonicalAgentIds == null || canonicalAgentIds.isEmpty()) {
            throw forbidden("At least one canonical Agent identity is required");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String canonicalAgentId : canonicalAgentIds) {
            requireAgentId(canonicalAgentId);
            rejectSystem(canonicalAgentId);
            if (!unique.add(canonicalAgentId)) {
                throw forbidden("Canonical Agent identities must be byte-exact and unique");
            }
        }
        List<String> ordered = new ArrayList<>(unique);
        ordered.sort(BYTE_EXACT_ORDER);
        for (String canonicalAgentId : ordered) {
            AgentIdentityRegistryEntity observed = registryDao.findExactByCanonicalInScope(
                    tenantId, clientId, ownerJiacn, canonicalAgentId);
            if (observed == null || observed.getBindingId() == null) {
                throw forbidden("Canonical Agent identity is not registered in this scope");
            }
            validateRegistry(observed, tenantId, clientId, ownerJiacn, canonicalAgentId, true);
            AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(observed.getBindingId());
            AgentIdentityRegistryEntity locked = registryDao.findExactByCanonicalInScopeForUpdate(
                    tenantId, clientId, ownerJiacn, canonicalAgentId);
            if (locked == null || !Objects.equals(observed.getId(), locked.getId())
                    || !Objects.equals(observed.getBindingId(), locked.getBindingId())) {
                throw forbidden("Agent identity changed while acquiring ownership locks");
            }
            validateRegistry(locked, tenantId, clientId, ownerJiacn, canonicalAgentId, true);
            requireActiveLifecycle(locked);
            requireBindingProjection(locked, binding, null, true);
        }
        return List.copyOf(canonicalAgentIds);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public String requirePersistedCanonicalAgentIdInScope(
            String tenantId, String clientId, String ownerJiacn, String canonicalAgentId) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireAgentId(canonicalAgentId);
        rejectSystem(canonicalAgentId);
        AgentIdentityRegistryEntity identity = registryDao.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, canonicalAgentId);
        if (identity == null) {
            throw forbidden("Persisted canonical Agent identity is not registered in this scope");
        }
        validateRegistry(identity, tenantId, clientId, ownerJiacn, canonicalAgentId, true);
        requirePersistedRegistry(identity);
        AgentPersonaBindingEntity binding = bindingDao.selectById(identity.getBindingId());
        requirePersistedBinding(identity, binding);
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
        return requireBindingProjection(identity, binding, acceptedLegacyAlias, true);
    }

    private AgentPersonaBindingEntity requireBindingProjection(
            AgentIdentityRegistryEntity identity, AgentPersonaBindingEntity binding,
            String acceptedLegacyAlias, boolean requireActive) {
        requireBindingScope(binding, identity.getTenantId(), identity.getClientId(),
                identity.getOwnerJiacn(), identity.getBindingId());
        if (requireActive && binding.getStatus() != AgentConstants.BINDING_STATUS_ACTIVE) {
            throw forbidden("Agent binding is inactive, missing, or outside identity scope");
        }
        if (Objects.equals(identity.getCanonicalAgentId(), binding.getAgentId())) {
            return binding;
        }
        if (acceptedLegacyAlias != null && !Objects.equals(acceptedLegacyAlias, binding.getAgentId())) {
            throw forbidden("Accepted legacy alias does not match the audited binding Agent ID");
        }
        AgentIdentityAliasEntity bindingAlias = requireActive
                ? aliasDao.findExactActiveLegacyAlias(identity.getTenantId(), identity.getClientId(),
                        identity.getOwnerJiacn(), binding.getAgentId())
                : aliasDao.findExactLegacyAlias(identity.getTenantId(), identity.getClientId(),
                        identity.getOwnerJiacn(), binding.getAgentId());
        validateBindingAlias(identity, binding, bindingAlias, requireActive);
        return binding;
    }

    private void requireBindingScope(AgentPersonaBindingEntity binding,
            String tenantId, String clientId, String ownerJiacn, Long expectedBindingId) {
        if (binding == null || binding.getId() == null || binding.getStatus() == null
                || !Objects.equals(expectedBindingId, binding.getId())
                || !Objects.equals(clientId, binding.getClientId())
                || !Objects.equals(ownerJiacn, binding.getJiacn())
                || binding.getTenantId() != null && !Objects.equals(tenantId, binding.getTenantId())
                || binding.getStatus() != AgentConstants.BINDING_STATUS_PROVISIONED
                    && binding.getStatus() != AgentConstants.BINDING_STATUS_ACTIVE
                    && binding.getStatus() != AgentConstants.BINDING_STATUS_SUSPENDED
                    && binding.getStatus() != AgentConstants.BINDING_STATUS_RETIRED
                || binding.getBoundAt() == null || binding.getBoundAt() <= 0
                || binding.getPersonaCode() == null || binding.getPersonaCode().isEmpty()
                || binding.getAgentId() == null || binding.getAgentId().isEmpty()) {
            throw forbidden("Agent binding is incomplete, missing, or outside identity scope");
        }
        requireExactText(binding.getPersonaCode(), "personaCode", 50);
        requireAgentId(binding.getAgentId());
    }

    private void validateBindingAlias(AgentIdentityRegistryEntity identity,
            AgentPersonaBindingEntity binding, AgentIdentityAliasEntity alias, boolean requireActive) {
        if (alias == null || alias.getId() == null || alias.getId() <= 0
                || !Objects.equals(alias.getRegistryId(), identity.getId())
                || !Objects.equals(alias.getCanonicalAgentId(), identity.getCanonicalAgentId())
                || !Objects.equals(alias.getAliasValue(), binding.getAgentId())
                || !Objects.equals(alias.getTenantId(), identity.getTenantId())
                || !Objects.equals(alias.getClientId(), identity.getClientId())
                || !Objects.equals(alias.getOwnerJiacn(), identity.getOwnerJiacn())
                || !AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID.equals(alias.getAliasType())
                || alias.getValidFrom() == null || alias.getValidFrom() <= 0
                || alias.getAuditReason() == null || alias.getAuditReason().isEmpty()
                || !alias.getAuditReason().equals(alias.getAuditReason().strip())
                || alias.getAuditReason().chars().anyMatch(Character::isISOControl)) {
            throw forbidden("Binding Agent ID does not match canonical identity or an audited alias");
        }
        boolean active = AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE.equals(alias.getAliasStatus())
                && alias.getValidTo() == null;
        boolean revoked = AgentConstants.IDENTITY_ALIAS_STATUS_REVOKED.equals(alias.getAliasStatus())
                && alias.getValidTo() != null && alias.getValidTo() >= alias.getValidFrom();
        if (requireActive ? !active : !active && !revoked) {
            throw forbidden("Binding Agent alias lifecycle is invalid");
        }
    }

    private void requirePersistedRegistry(AgentIdentityRegistryEntity identity) {
        if (identity.getId() == null || identity.getId() <= 0
                || identity.getBindingId() == null || identity.getBindingId() <= 0
                || identity.getProvisionedAt() == null || identity.getProvisionedAt() <= 0
                || identity.getAuditReason() == null || identity.getAuditReason().isEmpty()
                || !identity.getAuditReason().equals(identity.getAuditReason().strip())
                || identity.getAuditReason().chars().anyMatch(Character::isISOControl)) {
            throw forbidden("Persisted Agent identity registry is incomplete");
        }
        if (identity.getActivatedAt() == null || identity.getActivatedAt() <= 0
                || identity.getActivatedAt() < identity.getProvisionedAt()) {
            throw forbidden("Persisted Agent identity was never validly activated");
        }
        String lifecycle = identity.getLifecycleStatus();
        if (AgentConstants.IDENTITY_STATUS_ACTIVE.equals(lifecycle)) {
            if (identity.getSuspendedAt() != null || identity.getRetiredAt() != null) {
                throw forbidden("Persisted active Agent identity lifecycle is incomplete");
            }
        } else if (AgentConstants.IDENTITY_STATUS_SUSPENDED.equals(lifecycle)) {
            if (identity.getSuspendedAt() == null
                    || identity.getSuspendedAt() < identity.getActivatedAt()
                    || identity.getRetiredAt() != null) {
                throw forbidden("Persisted suspended Agent identity lifecycle is incomplete");
            }
        } else if (AgentConstants.IDENTITY_STATUS_RETIRED.equals(lifecycle)) {
            if (identity.getRetiredAt() == null
                    || identity.getRetiredAt() < identity.getActivatedAt()
                    || identity.getSuspendedAt() != null
                        && identity.getRetiredAt() < identity.getSuspendedAt()) {
                throw forbidden("Persisted retired Agent identity lifecycle is incomplete");
            }
        } else {
            throw forbidden("Persisted Agent identity was never activated");
        }
    }

    private void requirePersistedBinding(
            AgentIdentityRegistryEntity identity, AgentPersonaBindingEntity binding) {
        int expectedStatus = switch (identity.getLifecycleStatus()) {
            case AgentConstants.IDENTITY_STATUS_ACTIVE -> AgentConstants.BINDING_STATUS_ACTIVE;
            case AgentConstants.IDENTITY_STATUS_SUSPENDED -> AgentConstants.BINDING_STATUS_SUSPENDED;
            case AgentConstants.IDENTITY_STATUS_RETIRED -> AgentConstants.BINDING_STATUS_RETIRED;
            default -> throw forbidden("Persisted Agent identity lifecycle is unsupported");
        };
        requireBindingProjection(identity, binding, null, false);
        if (binding.getStatus() != expectedStatus) {
            throw forbidden("Persisted Agent identity and binding lifecycle do not match");
        }
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
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw forbidden(name + " must be nonblank, unpadded, and free of control characters");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private void rejectSystem(String agentId) {
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agentId)) {
            throw forbidden("System agent cannot be registered or resolved externally");
        }
    }

    private static int compareUtf8Unsigned(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private AgentServiceImpl.AgentBizException forbidden(String message) {
        return new AgentServiceImpl.AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, message);
    }
}
