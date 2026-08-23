package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStatusMonitorTest extends BaseMockTest {
    @Mock
    AgentRuntimeDao agentRuntimeDao;
    @Mock
    AgentPersonaBindingDao bindingDao;
    @Mock
    AgentIdentityRegistryDao identityRegistryDao;
    @Mock
    ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    @Mock
    AgentEventPublisher eventPublisher;

    AgentStatusMonitor monitor;

    @BeforeEach
    void setUpMonitor() {
        AgentStatusTransitionWorker worker = new AgentStatusTransitionWorker(
                bindingDao, identityRegistryDao, agentRuntimeDao, eventPublisherProvider);
        monitor = new AgentStatusMonitor(agentRuntimeDao, worker,
                new AgentScopePublicationCoordinator(), eventPublisherProvider);
        ReflectionTestUtils.setField(monitor, "heartbeatTimeoutSeconds", 60L);
        org.mockito.Mockito.lenient().when(agentRuntimeDao.updateById(
                org.mockito.ArgumentMatchers.any(AgentRuntimeEntity.class))).thenReturn(1);
    }

    @Test
    void keepsBuiltinSongjiangOnlineWhenHeartbeatScanRuns() {
        AgentRuntimeEntity songjiang = runtime(
                AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, 1L,
                AgentConstants.STATUS_ONLINE, "[]");
        AgentRuntimeEntity candidate = runtime(
                "agent-wuyong", 2L, AgentConstants.STATUS_ONLINE, "[\"stale\"]");
        candidate.setLastSeenAt(1L);
        AgentRuntimeEntity current = runtime(
                "agent-wuyong", 2L, AgentConstants.STATUS_ONLINE, "[\"current\"]");
        current.setLastSeenAt(1L);
        stubActiveLocks(current);

        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of());
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong()))
                .thenReturn(List.of(songjiang, candidate));
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of());

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, songjiang.getStatus());
        assertEquals(AgentConstants.STATUS_OFFLINE, current.getStatus());
        verify(agentRuntimeDao, never()).updateById(songjiang);
        verify(agentRuntimeDao).updateById(current);
    }

    @Test
    void staleTimeoutCandidateCannotOverwriteLockedRuntimeAbilities() {
        AgentRuntimeEntity stale = runtime(
                "agent-wuyong", 7L, AgentConstants.STATUS_BUSY, "[\"stale-skill\"]");
        stale.setLastSeenAt(1L);
        stale.setCurrentTaskId("task-stale");
        AgentRuntimeEntity current = runtime(
                "agent-wuyong", 7L, AgentConstants.STATUS_BUSY, "[\"fresh-skill\"]");
        current.setLastSeenAt(1L);
        current.setCurrentTaskId("task-current");
        stubActiveLocks(current);

        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of());
        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of());
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong())).thenReturn(List.of(stale));

        monitor.markHeartbeatTimedOutAgentsOffline();

        ArgumentCaptor<AgentRuntimeEntity> updated = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao).updateById(updated.capture());
        assertSame(current, updated.getValue());
        assertEquals("[\"fresh-skill\"]", updated.getValue().getAbilities());
        assertEquals(AgentConstants.STATUS_OFFLINE, updated.getValue().getStatus());
        assertEquals("[\"stale-skill\"]", stale.getAbilities());
        assertEquals(AgentConstants.STATUS_BUSY, stale.getStatus());

        InOrder lockOrder = inOrder(bindingDao, identityRegistryDao, agentRuntimeDao);
        lockOrder.verify(bindingDao).findByIdForUpdate(7L);
        lockOrder.verify(identityRegistryDao).findExactByBindingInScopeForUpdate(
                "juyiting", "jia_client", "juyiting", 7L);
        lockOrder.verify(agentRuntimeDao).findByAgentIdForUpdate("agent-wuyong");
        lockOrder.verify(agentRuntimeDao).updateById(current);
    }

    @Test
    void suspendedOrInactiveIdentityIsSkippedWithoutLockingOrUpdatingRuntime() {
        AgentRuntimeEntity provisioned = runtime(
                "agent-provisioned", 11L, AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        provisioned.setLastSeenAt(1L);
        AgentRuntimeEntity suspended = runtime(
                "agent-suspended", 12L, AgentConstants.STATUS_BUSY, "[\"review\"]");
        suspended.setLastSeenAt(1L);

        when(bindingDao.findByIdForUpdate(11L)).thenReturn(binding(provisioned));
        when(bindingDao.findByIdForUpdate(12L)).thenReturn(binding(suspended));
        when(identityRegistryDao.findExactByBindingInScopeForUpdate(
                "juyiting", "jia_client", "juyiting", 11L))
                .thenReturn(identity(provisioned, AgentConstants.IDENTITY_STATUS_PROVISIONED));
        when(identityRegistryDao.findExactByBindingInScopeForUpdate(
                "juyiting", "jia_client", "juyiting", 12L))
                .thenReturn(identity(suspended, AgentConstants.IDENTITY_STATUS_SUSPENDED));
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of());
        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of());
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong()))
                .thenReturn(List.of(provisioned, suspended));

        monitor.markHeartbeatTimedOutAgentsOffline();

        verify(agentRuntimeDao, never()).findByAgentIdForUpdate(org.mockito.ArgumentMatchers.anyString());
        verify(agentRuntimeDao, never()).updateById(org.mockito.ArgumentMatchers.any());
        assertEquals(AgentConstants.STATUS_ONLINE, provisioned.getStatus());
        assertEquals(AgentConstants.STATUS_BUSY, suspended.getStatus());
    }

    @Test
    void doesNotOfflineAHeartbeatCandidateWhenItStillOwnsALocalWebSocket() {
        AgentRuntimeEntity candidate = runtime(
                "agent-connected", 3L, AgentConstants.STATUS_ONLINE, "[\"stale\"]");
        candidate.setLastSeenAt(1L);
        AgentRuntimeEntity current = runtime(
                "agent-connected", 3L, AgentConstants.STATUS_ONLINE, "[\"fresh\"]");
        current.setLastSeenAt(1L);
        stubActiveLocks(current);

        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of("agent-connected"));
        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of(candidate));
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong())).thenReturn(List.of(candidate));

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, current.getStatus());
        assertEquals("[\"fresh\"]", current.getAbilities());
        verify(agentRuntimeDao, times(1)).updateById(current);
    }

    @Test
    void refreshConnectedNeverMovesLastSeenBackward() {
        AgentRuntimeEntity candidate = runtime(
                "agent-monotonic", 13L, AgentConstants.STATUS_ONLINE, "[\"stale\"]");
        long futureLastSeenAt = System.currentTimeMillis() + 60_000L;
        AgentRuntimeEntity current = runtime(
                "agent-monotonic", 13L, AgentConstants.STATUS_ONLINE, "[\"fresh\"]");
        current.setLastSeenAt(futureLastSeenAt);
        stubActiveLocks(current);

        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of("agent-monotonic"));
        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of(candidate));
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong())).thenReturn(List.of());

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(futureLastSeenAt, current.getLastSeenAt());
        verify(agentRuntimeDao).updateById(current);
    }

    @Test
    void monitorSkipsPublicationWhenIdentityWasSuspendedAfterTransitionCommit() {
        AgentRuntimeEntity candidate = runtime(
                "agent-raced-unbind", 14L, AgentConstants.STATUS_OFFLINE, "[\"stale\"]");
        AgentRuntimeEntity transitioned = runtime(
                "agent-raced-unbind", 14L, AgentConstants.STATUS_OFFLINE, "[\"fresh\"]");
        transitioned.setLastSeenAt(1L);
        AgentPersonaBindingEntity activeBinding = binding(transitioned);
        AgentIdentityRegistryEntity activeIdentity = identity(
                transitioned, AgentConstants.IDENTITY_STATUS_ACTIVE);
        AgentIdentityRegistryEntity suspendedIdentity = identity(
                transitioned, AgentConstants.IDENTITY_STATUS_SUSPENDED);
        when(bindingDao.findByIdForUpdate(14L)).thenReturn(activeBinding, activeBinding);
        when(identityRegistryDao.findExactByBindingInScopeForUpdate(
                "juyiting", "jia_client", "juyiting", 14L))
                .thenReturn(activeIdentity, suspendedIdentity);
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-raced-unbind"))
                .thenReturn(transitioned);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of("agent-raced-unbind"));
        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of(candidate));
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong())).thenReturn(List.of());

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, transitioned.getStatus());
        verify(eventPublisher, never()).publishAgentStatus(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(agentRuntimeDao, times(1)).findByAgentIdForUpdate("agent-raced-unbind");
    }

    @Test
    void monitorSkipsPublicationWhenRuntimeChangedAfterTransitionCommit() {
        AgentRuntimeEntity candidate = runtime(
                "agent-raced-presence", 15L, AgentConstants.STATUS_OFFLINE, "[\"stale\"]");
        AgentRuntimeEntity transitioned = runtime(
                "agent-raced-presence", 15L, AgentConstants.STATUS_OFFLINE, "[\"fresh\"]");
        transitioned.setLastSeenAt(1L);
        AgentRuntimeEntity changed = runtime(
                "agent-raced-presence", 15L, AgentConstants.STATUS_OFFLINE, "[\"newer\"]");
        changed.setLastSeenAt(2L);
        AgentPersonaBindingEntity activeBinding = binding(transitioned);
        AgentIdentityRegistryEntity activeIdentity = identity(
                transitioned, AgentConstants.IDENTITY_STATUS_ACTIVE);
        when(bindingDao.findByIdForUpdate(15L)).thenReturn(activeBinding);
        when(identityRegistryDao.findExactByBindingInScopeForUpdate(
                "juyiting", "jia_client", "juyiting", 15L)).thenReturn(activeIdentity);
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-raced-presence"))
                .thenReturn(transitioned, changed);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of("agent-raced-presence"));
        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of(candidate));
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong())).thenReturn(List.of());

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, transitioned.getStatus());
        verify(eventPublisher, never()).publishAgentStatus(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(agentRuntimeDao, times(2)).findByAgentIdForUpdate("agent-raced-presence");
    }

    @Test
    void refreshesLocallyConnectedAgentsWithoutOffliningAgentsOwnedByAnotherInstance() {
        AgentRuntimeEntity idleCandidate = runtime(
                "agent-idle", 4L, AgentConstants.STATUS_OFFLINE, "[\"stale-idle\"]");
        AgentRuntimeEntity idleCurrent = runtime(
                "agent-idle", 4L, AgentConstants.STATUS_OFFLINE, "[\"fresh-idle\"]");
        stubActiveLocks(idleCurrent);

        AgentRuntimeEntity busyCandidate = runtime(
                "agent-busy", 5L, AgentConstants.STATUS_ONLINE, "[\"stale-busy\"]");
        AgentRuntimeEntity busyCurrent = runtime(
                "agent-busy", 5L, AgentConstants.STATUS_ONLINE, "[\"fresh-busy\"]");
        busyCurrent.setCurrentTaskId("task-001");
        busyCurrent.setCurrentTaskTitle("处理悬赏");
        stubActiveLocks(busyCurrent);

        AgentRuntimeEntity disconnected = runtime(
                "agent-stale", 6L, AgentConstants.STATUS_BUSY, "[\"remote\"]");
        disconnected.setCurrentTaskId("task-002");
        disconnected.setCurrentTaskTitle("旧任务");

        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of("agent-idle", "agent-busy"));
        when(agentRuntimeDao.findByStatusAndAbility(null, null))
                .thenReturn(List.of(idleCandidate, busyCandidate, disconnected));
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong())).thenReturn(List.of());

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, idleCurrent.getStatus());
        assertEquals(AgentConstants.STATUS_BUSY, busyCurrent.getStatus());
        assertEquals("[\"fresh-idle\"]", idleCurrent.getAbilities());
        assertEquals("[\"fresh-busy\"]", busyCurrent.getAbilities());
        assertEquals(AgentConstants.STATUS_BUSY, disconnected.getStatus());
        assertEquals("task-002", disconnected.getCurrentTaskId());
        verify(agentRuntimeDao, times(2)).updateById(org.mockito.ArgumentMatchers.any(AgentRuntimeEntity.class));
    }

    private void stubActiveLocks(AgentRuntimeEntity current) {
        when(bindingDao.findByIdForUpdate(current.getBindingId())).thenReturn(binding(current));
        when(identityRegistryDao.findExactByBindingInScopeForUpdate(
                current.getOwnerJiacn(), current.getClientId(), current.getOwnerJiacn(),
                current.getBindingId()))
                .thenReturn(identity(current, AgentConstants.IDENTITY_STATUS_ACTIVE));
        when(agentRuntimeDao.findByAgentIdForUpdate(current.getAgentId())).thenReturn(current);
    }

    private AgentRuntimeEntity runtime(
            String agentId, long bindingId, String status, String abilities) {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setId(bindingId + 100L);
        runtime.setAgentId(agentId);
        runtime.setBindingId(bindingId);
        runtime.setClientId("jia_client");
        runtime.setOwnerJiacn("juyiting");
        runtime.setStatus(status);
        runtime.setAbilities(abilities);
        return runtime;
    }

    private AgentPersonaBindingEntity binding(AgentRuntimeEntity runtime) {
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(runtime.getBindingId());
        binding.setTenantId(runtime.getOwnerJiacn());
        binding.setClientId(runtime.getClientId());
        binding.setJiacn(runtime.getOwnerJiacn());
        binding.setAgentId(runtime.getAgentId());
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        return binding;
    }

    private AgentIdentityRegistryEntity identity(AgentRuntimeEntity runtime, String lifecycle) {
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setId(runtime.getBindingId() + 200L);
        identity.setTenantId(runtime.getOwnerJiacn());
        identity.setClientId(runtime.getClientId());
        identity.setOwnerJiacn(runtime.getOwnerJiacn());
        identity.setBindingId(runtime.getBindingId());
        identity.setCanonicalAgentId(runtime.getAgentId());
        identity.setLifecycleStatus(lifecycle);
        return identity;
    }
}
