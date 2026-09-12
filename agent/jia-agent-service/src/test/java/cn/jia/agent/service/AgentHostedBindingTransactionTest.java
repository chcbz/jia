package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.dao.AgentHostedProfileDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentHostedBindingTransactionTest {
    private static final Scope SCOPE = new Scope("owner-a", "client-a", "owner-a");
    private static final String AGENT_ID = "agt_0123456789abcdef0123456789abcdef";

    @Test
    void disabledFileCrashWindowLocksFreshRuntimeThenPublishesOfflineOnceAfterCommit() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.SUSPENDING, 4L);
        AgentRuntimeEntity runtime = runtime(AgentConstants.STATUS_ONLINE);
        runtime.setEndpoint("fresh-endpoint");
        OauthApiKeyEntity key = key(0);

        when(fixture.bindingDao.findByIdForUpdate(12L)).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(hosted);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(runtime);
        when(fixture.runtimeDao.findByAgentId(AGENT_ID)).thenReturn(runtime);
        when(fixture.apiKeys.get("key-12")).thenReturn(key);
        when(fixture.bindingDao.updateById(binding)).thenReturn(1);
        when(fixture.runtimeDao.clearBindingAfterUnbind(
                eq(31L), eq(AGENT_ID), eq(12L), eq("client-a"), eq("owner-a"), anyLong()))
                .thenReturn(1);
        when(fixture.hostedDao.transition(5L, AgentHostedProfileState.SUSPENDING, 4L,
                AgentHostedProfileState.SUSPENDED, 5L, false)).thenReturn(1);

        beginTransactionSynchronization();
        try {
            AgentHostedProfileEntity result = fixture.transaction.completeUnbind(SCOPE, 12L, 4L, 5L);

            assertEquals(AgentHostedProfileState.SUSPENDED, result.getLifecycleState());
            assertEquals(AgentConstants.BINDING_STATUS_SUSPENDED, binding.getStatus());
            assertEquals(AgentConstants.STATUS_OFFLINE, runtime.getStatus());
            assertNull(runtime.getClientId());
            assertNull(runtime.getOwnerJiacn());
            assertNull(runtime.getBindingId());
            assertNull(runtime.getPersonaCode());
            assertNull(runtime.getPersonaName());
            assertNull(runtime.getEndpoint());
            assertNull(runtime.getTokenHash());
            verify(fixture.eventPublisher, never()).publishAgentStatus(any(), any(), any());
            InOrder order = inOrder(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao);
            order.verify(fixture.bindingDao).findByIdForUpdate(12L);
            order.verify(fixture.hostedDao).findExactForUpdate("owner-a", "client-a", "owner-a", 12L);
            order.verify(fixture.runtimeDao).findByAgentIdForUpdate(AGENT_ID);
            order.verify(fixture.bindingDao).updateById(binding);
            order.verify(fixture.runtimeDao).clearBindingAfterUnbind(
                    eq(31L), eq(AGENT_ID), eq(12L), eq("client-a"), eq("owner-a"), anyLong());
            commitSynchronizations();
        } finally {
            clearTransactionSynchronization();
        }

        ArgumentCaptor<AgentRuntimeDTO> snapshot = ArgumentCaptor.forClass(AgentRuntimeDTO.class);
        verify(fixture.eventPublisher, times(1)).publishAgentStatus(
                eq("client-a"), eq("owner-a"), snapshot.capture());
        assertEquals(AGENT_ID, snapshot.getValue().getAgentId());
        assertEquals(AgentConstants.STATUS_OFFLINE, snapshot.getValue().getStatus());
        assertNull(snapshot.getValue().getOwnerJiacn());
        assertNull(snapshot.getValue().getEndpoint());
        verify(fixture.apiKeys, never()).update(any());
        verify(fixture.identityService).suspendForBinding("owner-a", "client-a", "owner-a", 12L);
    }

    @Test
    void localUnbindRejectsFreshActiveTaskBeforeAnyMutationOrPublication() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentRuntimeEntity freshRuntime = runtime(AgentConstants.STATUS_BUSY);
        freshRuntime.setCurrentTaskId("fresh-task");
        freshRuntime.setCurrentTaskTitle("Fresh task");

        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(null);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(freshRuntime);

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.prepareUnbind(SCOPE, "wuyong"));

        assertEquals(AgentErrorConstants.AGENT_BUSY, failure.getCode());
        assertEquals(AgentConstants.BINDING_STATUS_ACTIVE, binding.getStatus());
        assertEquals(AgentConstants.STATUS_BUSY, freshRuntime.getStatus());
        verify(fixture.bindingDao, never()).updateById(any());
        verify(fixture.runtimeDao, never()).clearBindingAfterUnbind(
                anyLong(), anyString(), anyLong(), anyString(), anyString(), anyLong());
        verify(fixture.identityService, never()).suspendForBinding(anyString(), anyString(),
                anyString(), anyLong());
        verify(fixture.eventPublisher, never()).publishAgentStatus(any(), any(), any());
    }

    @Test
    void completeUnbindRejectsFreshActiveTaskBeforeCredentialOrDatabaseMutation() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.SUSPENDING, 4L);
        AgentRuntimeEntity runtime = runtime(AgentConstants.STATUS_BUSY);
        runtime.setCurrentTaskId("fresh-task");

        when(fixture.bindingDao.findByIdForUpdate(12L)).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity());
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(hosted);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(runtime);

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.completeUnbind(SCOPE, 12L, 4L, 5L));

        assertEquals(AgentErrorConstants.AGENT_BUSY, failure.getCode());
        verifyNoInteractions(fixture.apiKeys);
        verify(fixture.bindingDao, never()).updateById(any());
        verify(fixture.runtimeDao, never()).clearBindingAfterUnbind(
                anyLong(), anyString(), anyLong(), anyString(), anyString(), anyLong());
        verify(fixture.identityService, never()).suspendForBinding(anyString(), anyString(),
                anyString(), anyLong());
        verify(fixture.hostedDao, never()).transition(anyLong(), anyString(), anyLong(),
                anyString(), anyLong(), anyBoolean());
    }

    @Test
    void localUnbindRejectsDurableAssignedWorkWhenRuntimeProjectionIsIdle() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentRuntimeEntity runtime = runtime(AgentConstants.STATUS_ONLINE);
        AgentTaskMetaEntity assigned = new AgentTaskMetaEntity();
        assigned.setTaskId("durable-task");
        assigned.setTenantId("owner-a");
        assigned.setClientId("client-a");
        assigned.setAssignedAgentId(AGENT_ID);
        assigned.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);

        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity());
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(null);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(runtime);
        when(fixture.taskMetaDao.findDurableActiveAssignmentByAgentForUpdate(
                "owner-a", "client-a", AGENT_ID)).thenReturn(assigned);

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.prepareUnbind(SCOPE, "wuyong"));

        assertEquals(AgentErrorConstants.AGENT_BUSY, failure.getCode());
        InOrder order = inOrder(fixture.bindingDao, fixture.hostedDao,
                fixture.runtimeDao, fixture.taskMetaDao);
        order.verify(fixture.bindingDao).findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong");
        order.verify(fixture.hostedDao).findExactForUpdate(
                "owner-a", "client-a", "owner-a", 12L);
        order.verify(fixture.runtimeDao).findByAgentIdForUpdate(AGENT_ID);
        order.verify(fixture.taskMetaDao).findDurableActiveAssignmentByAgentForUpdate(
                "owner-a", "client-a", AGENT_ID);
        verify(fixture.bindingDao, never()).updateById(any());
        verify(fixture.runtimeDao, never()).clearBindingAfterUnbind(
                anyLong(), anyString(), anyLong(), anyString(), anyString(), anyLong());
        verify(fixture.identityService, never()).suspendForBinding(
                anyString(), anyString(), anyString(), anyLong());
        verify(fixture.eventPublisher, never()).publishAgentStatus(any(), any(), any());
    }

    @Test
    void runtimeDetachCasFailureDoesNotClearCapturedRuntimeAndFailsTransaction() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentRuntimeEntity runtime = runtime(AgentConstants.STATUS_ONLINE);
        runtime.setEndpoint("wss://agent.example");
        runtime.setTokenHash("sensitive-hash");

        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity());
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(null);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(runtime);
        when(fixture.bindingDao.updateById(binding)).thenReturn(1);
        when(fixture.runtimeDao.clearBindingAfterUnbind(
                eq(31L), eq(AGENT_ID), eq(12L), eq("client-a"), eq("owner-a"), anyLong()))
                .thenReturn(0);

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.prepareUnbind(SCOPE, "wuyong"));

        assertEquals(AgentErrorConstants.AGENT_ERROR, failure.getCode());
        assertEquals("client-a", runtime.getClientId());
        assertEquals("owner-a", runtime.getOwnerJiacn());
        assertEquals(12L, runtime.getBindingId());
        assertEquals("wss://agent.example", runtime.getEndpoint());
        assertEquals("sensitive-hash", runtime.getTokenHash());
        assertEquals(AgentConstants.STATUS_ONLINE, runtime.getStatus());
        verify(fixture.eventPublisher, never()).publishAgentStatus(any(), any(), any());
    }

    @Test
    void localUnbindRollbackPublishesZeroSnapshots() {
        Fixture fixture = fixture();
        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding());
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity());
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(null);
        AgentRuntimeEntity runtime = runtime(AgentConstants.STATUS_ONLINE);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(runtime);
        when(fixture.bindingDao.updateById(any())).thenReturn(1);
        when(fixture.runtimeDao.clearBindingAfterUnbind(
                eq(31L), eq(AGENT_ID), eq(12L), eq("client-a"), eq("owner-a"), anyLong()))
                .thenReturn(1);

        beginTransactionSynchronization();
        try {
            fixture.transaction.prepareUnbind(SCOPE, "wuyong");
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            // Simulated rollback: clear synchronization without invoking afterCommit.
        } finally {
            clearTransactionSynchronization();
        }
        verify(fixture.eventPublisher, never()).publishAgentStatus(any(), any(), any());
    }

    @Test
    void repairUsesExpectedDurableStateCheckpointAndGenerationSoStaleLoserCannotRegressActive() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentHostedProfileEntity winner = hosted(AgentHostedProfileState.ACTIVE, 8L);
        when(fixture.bindingDao.findByIdForUpdate(12L)).thenReturn(binding);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(winner);
        when(fixture.hostedDao.markRepair(5L, AgentHostedProfileState.FILE_ENABLED,
                null, 7L, AgentHostedProfileState.FILE_ENABLED,
                "HOSTED_PROFILE_FAILURE:IllegalStateException")).thenReturn(0);

        fixture.transaction.markRepair(SCOPE, 12L, AgentHostedProfileState.FILE_ENABLED,
                null, 7L, AgentHostedProfileState.FILE_ENABLED,
                new IllegalStateException("cdx_secret at /home/isp/private/auth.json"));

        verify(fixture.hostedDao).markRepair(5L, AgentHostedProfileState.FILE_ENABLED,
                null, 7L, AgentHostedProfileState.FILE_ENABLED,
                "HOSTED_PROFILE_FAILURE:IllegalStateException");
        assertEquals(AgentHostedProfileState.ACTIVE, winner.getLifecycleState());
        assertEquals(8L, winner.getGeneration());
    }

    @Test
    void resumeRepairCasIncludesStoredCheckpointAndGenerationAfterBindingHostedRuntimeLockOrder() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.REPAIR_REQUIRED, 6L);
        hosted.setResumeState(AgentHostedProfileState.STAGED_DISABLED);
        AgentRuntimeEntity runtime = runtime(AgentConstants.STATUS_OFFLINE);
        OauthApiKeyEntity key = key(1);

        when(fixture.bindingDao.findByIdForUpdate(12L)).thenReturn(binding);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(hosted);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(runtime);
        when(fixture.hostedDao.resumeRepair(5L, AgentHostedProfileState.STAGED_DISABLED, 6L)).thenReturn(1);
        when(fixture.apiKeys.get("key-12")).thenReturn(key);
        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());

        AgentHostedBindingTransaction.Prepared result = fixture.transaction.resumeRepair(SCOPE, 12L);

        assertEquals(AgentHostedProfileState.STAGED_DISABLED, result.hosted().getLifecycleState());
        assertNull(result.hosted().getResumeState());
        InOrder order = inOrder(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao);
        order.verify(fixture.bindingDao).findByIdForUpdate(12L);
        order.verify(fixture.hostedDao).findExactForUpdate("owner-a", "client-a", "owner-a", 12L);
        order.verify(fixture.runtimeDao).findByAgentIdForUpdate(AGENT_ID);
        order.verify(fixture.hostedDao).resumeRepair(5L, AgentHostedProfileState.STAGED_DISABLED, 6L);
    }

    @Test
    void existingPrepareRejectsExhaustedGenerationBeforeRuntimeOrHostedMutation() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.PREPARED, Long.MAX_VALUE);
        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.identityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(hosted);

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.prepareHosted(SCOPE, "wuyong"));

        verifyNoInteractions(fixture.apiKeys);
        verify(fixture.runtimeDao, never()).findByAgentId(any());
        verify(fixture.runtimeDao, never()).insert(any());
        verify(fixture.hostedDao, never()).insert(any());
        verify(fixture.hostedDao, never()).transition(anyLong(), anyString(), anyLong(),
                anyString(), anyLong(), anyBoolean());
        verify(fixture.hostedDao, never()).markRepair(anyLong(), anyString(), any(), anyLong(),
                anyString(), anyString());
    }

    @Test
    void activeUnbindRejectsExhaustedGenerationBeforeRuntimeOrDatabaseMutation() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.ACTIVE, Long.MAX_VALUE);
        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(hosted);

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.prepareUnbind(SCOPE, "wuyong"));

        verify(fixture.runtimeDao, never()).findByAgentIdForUpdate(any());
        verify(fixture.hostedDao, never()).resumeRepair(anyLong(), anyString(), anyLong());
        verify(fixture.hostedDao, never()).transition(anyLong(), anyString(), anyLong(),
                anyString(), anyLong(), anyBoolean());
        verify(fixture.bindingDao, never()).updateById(any());
        verify(fixture.runtimeDao, never()).clearBindingAfterUnbind(
                anyLong(), anyString(), anyLong(), anyString(), anyString(), anyLong());
        verify(fixture.identityService, never()).suspendForBinding(anyString(), anyString(),
                anyString(), anyLong());
        verifyNoInteractions(fixture.apiKeys);
    }

    @Test
    void directAdvanceTransitionRejectsExhaustedGenerationBeforeDatabaseLookup() {
        Fixture fixture = fixture();

        assertThrows(AgentServiceImpl.AgentBizException.class, () -> fixture.transaction.transition(
                SCOPE, 12L, AgentHostedProfileState.PREPARED, Long.MAX_VALUE,
                AgentHostedProfileState.STAGED_DISABLED, Long.MAX_VALUE, true));

        verifyNoInteractions(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao,
                fixture.apiKeys);
    }

    @Test
    void exhaustedRepairCheckpointConvergesWithoutResumeFileOrDatabaseMutation() {
        Fixture fixture = fixture();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.REPAIR_REQUIRED, Long.MAX_VALUE);
        hosted.setResumeState(AgentHostedProfileState.PREPARED);
        when(fixture.bindingDao.findByIdForUpdate(12L)).thenReturn(binding());
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(hosted);

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.resumeRepair(SCOPE, 12L));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> fixture.transaction.resumeRepair(SCOPE, 12L));

        assertEquals(AgentHostedProfileState.REPAIR_REQUIRED, hosted.getLifecycleState());
        assertEquals(AgentHostedProfileState.PREPARED, hosted.getResumeState());
        assertEquals(Long.MAX_VALUE, hosted.getGeneration());
        verify(fixture.hostedDao, never()).resumeRepair(anyLong(), anyString(), anyLong());
        verify(fixture.hostedDao, never()).transition(anyLong(), anyString(), anyLong(),
                anyString(), anyLong(), anyBoolean());
        verifyNoInteractions(fixture.runtimeDao, fixture.apiKeys);
    }

    @Test
    void serverBindRepairRequiredPreparedExhaustionRejectsBeforeMissingRuntimeApiKeyOrHostedMutationAndConverges() {
        Fixture fixture = fixture();
        AgentService agentService = mock(AgentService.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentPersonaProvisioningService service =
                new AgentPersonaProvisioningService(agentService, fixture.transaction, publisher);
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.REPAIR_REQUIRED, Long.MAX_VALUE);
        hosted.setResumeState(AgentHostedProfileState.PREPARED);
        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.identityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(hosted);
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.bind(SCOPE, "wuyong", "server"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.bind(SCOPE, "wuyong", "server"));

        assertEquals(AgentHostedProfileState.REPAIR_REQUIRED, hosted.getLifecycleState());
        assertEquals(AgentHostedProfileState.PREPARED, hosted.getResumeState());
        assertEquals(Long.MAX_VALUE, hosted.getGeneration());
        verifyNoInteractions(fixture.runtimeDao, agentService, publisher, fixture.apiKeys);
        verify(fixture.hostedDao, never()).insert(any());
        verify(fixture.hostedDao, never()).resumeRepair(anyLong(), anyString(), anyLong());
        verify(fixture.hostedDao, never()).transition(anyLong(), anyString(), anyLong(),
                anyString(), anyLong(), anyBoolean());
        verify(fixture.hostedDao, never()).markRepair(anyLong(), anyString(), any(), anyLong(),
                anyString(), anyString());
    }

    @Test
    void delayedUnbindCallbackSkipsDetachedSnapshotAfterRuntimeWasRebound() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentRuntimeEntity unbound = runtime(AgentConstants.STATUS_ONLINE);
        unbound.setEndpoint("precommit-endpoint");
        AgentRuntimeEntity reconnected = runtime(AgentConstants.STATUS_ONLINE);
        reconnected.setEndpoint("newer-online-endpoint");
        reconnected.setLastSeenAt(System.currentTimeMillis() + 1_000L);

        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(null);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(unbound);
        when(fixture.runtimeDao.findByAgentId(AGENT_ID)).thenReturn(reconnected);
        when(fixture.bindingDao.updateById(binding)).thenReturn(1);
        when(fixture.runtimeDao.clearBindingAfterUnbind(
                eq(31L), eq(AGENT_ID), eq(12L), eq("client-a"), eq("owner-a"), anyLong()))
                .thenReturn(1);

        List<TransactionSynchronization> delayedCallbacks;
        beginTransactionSynchronization();
        try {
            assertNull(fixture.transaction.prepareUnbind(SCOPE, "wuyong"));
            assertEquals(AgentConstants.STATUS_OFFLINE, unbound.getStatus());
            delayedCallbacks = TransactionSynchronizationManager.getSynchronizations();
        } finally {
            clearTransactionSynchronization();
        }

        AgentRuntimeDTO alreadyPublishedOnline = new AgentRuntimeDTO();
        alreadyPublishedOnline.setAgentId(AGENT_ID);
        alreadyPublishedOnline.setStatus(AgentConstants.STATUS_ONLINE);
        alreadyPublishedOnline.setEndpoint("newer-online-endpoint");
        fixture.scopePublicationCoordinator.execute("client-a", "owner-a", () ->
                fixture.eventPublisher.publishAgentStatus(
                        "client-a", "owner-a", alreadyPublishedOnline));

        delayedCallbacks.forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<AgentRuntimeDTO> publications = ArgumentCaptor.forClass(AgentRuntimeDTO.class);
        verify(fixture.eventPublisher, times(1)).publishAgentStatus(
                eq("client-a"), eq("owner-a"), publications.capture());
        assertEquals(List.of(AgentConstants.STATUS_ONLINE),
                publications.getAllValues().stream().map(AgentRuntimeDTO::getStatus).toList());
        assertEquals("newer-online-endpoint", publications.getValue().getEndpoint());
        verify(fixture.runtimeDao).findByAgentId(AGENT_ID);
    }

    @Test
    void hostedPublicationRereadUsesFreshReadOnlyTransactionBoundary() throws Exception {
        var boundMethod = AgentHostedRuntimePublicationWorker.class.getMethod(
                "revalidateForPublication", Scope.class, String.class, long.class);
        var boundTransaction = boundMethod.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class);
        var detachedMethod = AgentHostedRuntimePublicationWorker.class.getMethod(
                "revalidateDetachedForPublication", Scope.class, String.class, long.class);
        var detachedTransaction = detachedMethod.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class);

        assertNotNull(boundTransaction);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                boundTransaction.propagation());
        assertTrue(boundTransaction.readOnly());
        assertNotNull(detachedTransaction);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                detachedTransaction.propagation());
        assertTrue(detachedTransaction.readOnly());
    }

    @Test
    void hostedPublicationKeepsBoundAndDetachedRuntimeContractsDistinct() {
        AgentRuntimeDao runtimeDao = mock(AgentRuntimeDao.class);
        AgentHostedRuntimePublicationWorker worker =
                new AgentHostedRuntimePublicationWorker(runtimeDao);
        AgentRuntimeEntity bound = runtime(AgentConstants.STATUS_ONLINE);
        AgentRuntimeEntity detached = runtime(AgentConstants.STATUS_OFFLINE);
        detached.setBindingId(null);
        detached.setClientId(null);
        detached.setOwnerJiacn(null);
        detached.setPersonaCode(null);
        detached.setPersonaName(null);

        when(runtimeDao.findByAgentId(AGENT_ID)).thenReturn(bound, detached, detached);

        assertSame(bound, worker.revalidateForPublication(SCOPE, AGENT_ID, 12L));
        assertNull(worker.revalidateForPublication(SCOPE, AGENT_ID, 12L));
        assertSame(detached,
                worker.revalidateDetachedForPublication(SCOPE, AGENT_ID, 31L));
    }

    @Test
    void hostedLifecycleGraphCoversEveryLegitimateEdge() {
        assertDoesNotThrow(() -> AgentHostedGeneration.requireTransition(
                AgentHostedProfileState.PREPARED, 0L,
                AgentHostedProfileState.STAGED_DISABLED, 1L));
        assertDoesNotThrow(() -> AgentHostedGeneration.requireTransition(
                AgentHostedProfileState.STAGED_DISABLED, 1L,
                AgentHostedProfileState.FILE_ENABLED, 2L));
        assertDoesNotThrow(() -> AgentHostedGeneration.requireTransition(
                AgentHostedProfileState.FILE_ENABLED, 2L,
                AgentHostedProfileState.ACTIVE, 2L));
        assertDoesNotThrow(() -> AgentHostedGeneration.requireTransition(
                AgentHostedProfileState.ACTIVE, 2L,
                AgentHostedProfileState.SUSPENDING, 2L));
        assertDoesNotThrow(() -> AgentHostedGeneration.requireTransition(
                AgentHostedProfileState.SUSPENDING, 2L,
                AgentHostedProfileState.SUSPENDED, 3L));
    }

    @Test
    void transitionRejectsIllegalAdvancingEdgeBeforeDaoInteractions() {
        Fixture fixture = fixture();

        assertThrows(AgentServiceImpl.AgentBizException.class, () -> fixture.transaction.transition(
                SCOPE, 12L, AgentHostedProfileState.PREPARED, 4L,
                AgentHostedProfileState.SUSPENDED, 5L, false));

        verifyNoInteractions(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao,
                fixture.apiKeys);
    }

    @Test
    void transitionRejectsIllegalSameGenerationEdgeBeforeDaoInteractions() {
        Fixture fixture = fixture();

        assertThrows(AgentServiceImpl.AgentBizException.class, () -> fixture.transaction.transition(
                SCOPE, 12L, AgentHostedProfileState.FILE_ENABLED, 4L,
                AgentHostedProfileState.SUSPENDED, 4L, false));

        verifyNoInteractions(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao,
                fixture.apiKeys);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        AgentRuntimeDao runtimeDao = mock(AgentRuntimeDao.class);
        AgentIdentityService identityService = mock(AgentIdentityService.class);
        AgentPersonaDao personaDao = mock(AgentPersonaDao.class);
        AgentPersonaBindingDao bindingDao = mock(AgentPersonaBindingDao.class);
        AgentHostedProfileDao hostedDao = mock(AgentHostedProfileDao.class);
        AgentTaskMetaDao taskMetaDao = mock(AgentTaskMetaDao.class);
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        ObjectProvider<ApiKeyService> apiKeyProvider = mock(ObjectProvider.class);
        when(apiKeyProvider.getIfAvailable()).thenReturn(apiKeys);
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        ObjectProvider<AgentEventPublisher> eventPublisherProvider = mock(ObjectProvider.class);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        AgentScopePublicationCoordinator scopePublicationCoordinator =
                new AgentScopePublicationCoordinator();
        AgentHostedRuntimePublicationWorker runtimePublicationWorker =
                new AgentHostedRuntimePublicationWorker(runtimeDao);
        AgentHostedBindingTransaction transaction = new AgentHostedBindingTransaction(
                runtimeDao, identityService, personaDao, bindingDao, hostedDao, taskMetaDao,
                apiKeyProvider,
                eventPublisherProvider, scopePublicationCoordinator, runtimePublicationWorker);
        return new Fixture(transaction, runtimeDao, identityService, personaDao, bindingDao,
                hostedDao, taskMetaDao, apiKeys, eventPublisher, scopePublicationCoordinator);
    }

    private static AgentPersonaBindingEntity binding() {
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(12L);
        binding.setTenantId("owner-a");
        binding.setClientId("client-a");
        binding.setJiacn("owner-a");
        binding.setPersonaCode("wuyong");
        binding.setAgentId(AGENT_ID);
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        return binding;
    }

    private static AgentIdentityRegistryEntity identity() {
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setCanonicalAgentId(AGENT_ID);
        return identity;
    }

    private static AgentHostedProfileEntity hosted(String state, long generation) {
        AgentHostedProfileEntity hosted = new AgentHostedProfileEntity();
        hosted.setId(5L);
        hosted.setBindingId(12L);
        hosted.setTenantId("owner-a");
        hosted.setClientId("client-a");
        hosted.setOwnerJiacn("owner-a");
        hosted.setCanonicalAgentId(AGENT_ID);
        hosted.setPersonaCode("wuyong");
        hosted.setProfileKey(AgentHostedBindingTransaction.scopeDigest(SCOPE) + 12L);
        hosted.setApiKeyId("key-12");
        hosted.setLifecycleState(state);
        hosted.setGeneration(generation);
        hosted.setDesiredEnabled(!AgentHostedProfileState.SUSPENDING.equals(state));
        return hosted;
    }

    private static AgentRuntimeEntity runtime(String status) {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setId(31L);
        runtime.setAgentId(AGENT_ID);
        runtime.setBindingId(12L);
        runtime.setClientId("client-a");
        runtime.setOwnerJiacn("owner-a");
        runtime.setName("Wu Yong");
        runtime.setPersonaCode("wuyong");
        runtime.setPersonaName("Strategist");
        runtime.setStatus(status);
        return runtime;
    }

    private static AgentPersonaEntity persona() {
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setPersonaCode("wuyong");
        persona.setName("Wu Yong");
        persona.setTitle("Strategist");
        return persona;
    }

    private static OauthApiKeyEntity key(int status) {
        OauthApiKeyEntity key = new OauthApiKeyEntity();
        key.setId("key-12");
        key.setClientId("client-a");
        key.setJiacn("owner-a");
        key.setApiKey("cdx_0123456789abcdef0123456789abcdef");
        key.setStatus(status);
        return key;
    }

    private static void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void commitSynchronizations() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    private static void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private record Fixture(AgentHostedBindingTransaction transaction,
            AgentRuntimeDao runtimeDao,
            AgentIdentityService identityService,
            AgentPersonaDao personaDao,
            AgentPersonaBindingDao bindingDao,
            AgentHostedProfileDao hostedDao,
            AgentTaskMetaDao taskMetaDao,
            ApiKeyService apiKeys,
            AgentEventPublisher eventPublisher,
            AgentScopePublicationCoordinator scopePublicationCoordinator) {
    }
}
