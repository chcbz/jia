package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.event.AgentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Applies one monitor transition per transaction using binding -> identity -> runtime lock order. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStatusTransitionWorker {
    private final AgentPersonaBindingDao bindingDao;
    private final AgentIdentityRegistryDao identityRegistryDao;
    private final AgentRuntimeDao runtimeDao;
    private final ObjectProvider<AgentEventPublisher> eventPublisherProvider;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Transition refreshConnected(AgentRuntimeEntity candidate, long observedAt) {
        AgentRuntimeEntity current = lockCurrentRuntime(candidate);
        if (current == null || !isCurrentlyConnected(current.getAgentId())) {
            return null;
        }

        String nextStatus = hasText(current.getCurrentTaskId())
                ? AgentConstants.STATUS_BUSY : AgentConstants.STATUS_ONLINE;
        boolean statusChanged = !nextStatus.equals(current.getStatus());
        current.setLastSeenAt(observedAt);
        current.setStatus(nextStatus);
        requireUpdated(runtimeDao.updateById(current), current.getAgentId());
        return new Transition(current, statusChanged);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Transition markHeartbeatTimedOutOffline(AgentRuntimeEntity candidate, long cutoffTime) {
        AgentRuntimeEntity current = lockCurrentRuntime(candidate);
        if (current == null || isCurrentlyConnected(current.getAgentId())) {
            return null;
        }
        if (AgentConstants.STATUS_OFFLINE.equals(current.getStatus())) {
            return null;
        }
        Long lastSeenAt = current.getLastSeenAt();
        if (lastSeenAt != null && lastSeenAt >= cutoffTime) {
            return null;
        }

        current.setStatus(AgentConstants.STATUS_OFFLINE);
        current.setCurrentTaskId(null);
        current.setCurrentTaskTitle(null);
        current.setErrorMessage(null);
        requireUpdated(runtimeDao.updateById(current), current.getAgentId());
        return new Transition(current, true);
    }

    private AgentRuntimeEntity lockCurrentRuntime(AgentRuntimeEntity candidate) {
        if (!hasExactScope(candidate) || candidate.getBindingId() == null
                || candidate.getBindingId() <= 0
                || AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(candidate.getAgentId())) {
            return null;
        }

        String tenantId = candidate.getOwnerJiacn();
        String clientId = candidate.getClientId();
        long bindingId = candidate.getBindingId();
        AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(bindingId);
        if (!isActiveBinding(binding, tenantId, clientId, bindingId)) {
            return null;
        }

        AgentIdentityRegistryEntity identity = identityRegistryDao.findExactByBindingInScopeForUpdate(
                tenantId, clientId, tenantId, bindingId);
        if (!isActiveIdentity(identity, tenantId, clientId, bindingId, candidate.getAgentId())) {
            return null;
        }

        AgentRuntimeEntity current = runtimeDao.findByAgentIdForUpdate(candidate.getAgentId());
        if (!isExactCurrentRuntime(current, tenantId, clientId, bindingId, candidate.getAgentId())) {
            return null;
        }
        return current;
    }

    private boolean isCurrentlyConnected(String agentId) {
        AgentEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (publisher == null) {
            return false;
        }
        try {
            return Optional.ofNullable(publisher.connectedAgentIds())
                    .orElseGet(Set::of)
                    .contains(agentId);
        } catch (RuntimeException failure) {
            log.warn("Skipping Agent monitor transition because socket ownership could not be checked: agentId={}, failureType={}",
                    agentId, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private boolean hasExactScope(AgentRuntimeEntity candidate) {
        return candidate != null
                && hasExactText(candidate.getAgentId())
                && hasExactText(candidate.getClientId())
                && hasExactText(candidate.getOwnerJiacn());
    }

    private boolean isActiveBinding(AgentPersonaBindingEntity binding,
            String tenantId, String clientId, long bindingId) {
        return binding != null
                && Objects.equals(binding.getId(), bindingId)
                && Objects.equals(binding.getClientId(), clientId)
                && Objects.equals(binding.getJiacn(), tenantId)
                && (binding.getTenantId() == null || Objects.equals(binding.getTenantId(), tenantId))
                && binding.getStatus() != null
                && binding.getStatus() == AgentConstants.BINDING_STATUS_ACTIVE;
    }

    private boolean isActiveIdentity(AgentIdentityRegistryEntity identity,
            String tenantId, String clientId, long bindingId, String agentId) {
        return identity != null
                && Objects.equals(identity.getTenantId(), tenantId)
                && Objects.equals(identity.getClientId(), clientId)
                && Objects.equals(identity.getOwnerJiacn(), tenantId)
                && Objects.equals(identity.getBindingId(), bindingId)
                && Objects.equals(identity.getCanonicalAgentId(), agentId)
                && AgentConstants.IDENTITY_STATUS_ACTIVE.equals(identity.getLifecycleStatus());
    }

    private boolean isExactCurrentRuntime(AgentRuntimeEntity runtime,
            String tenantId, String clientId, long bindingId, String agentId) {
        return runtime != null
                && Objects.equals(runtime.getAgentId(), agentId)
                && Objects.equals(runtime.getClientId(), clientId)
                && Objects.equals(runtime.getOwnerJiacn(), tenantId)
                && Objects.equals(runtime.getBindingId(), bindingId);
    }

    private boolean hasExactText(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void requireUpdated(int updated, String agentId) {
        if (updated != 1) {
            throw new IllegalStateException("Agent runtime monitor update failed: " + agentId);
        }
    }

    public record Transition(AgentRuntimeEntity runtime, boolean statusChanged) {
    }
}
