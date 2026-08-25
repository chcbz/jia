package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.dao.AgentHostedProfileDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
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
        when(fixture.apiKeys.get("key-12")).thenReturn(key);
        when(fixture.bindingDao.updateById(binding)).thenReturn(1);
        when(fixture.runtimeDao.updateById(runtime)).thenReturn(1);
        when(fixture.hostedDao.transition(5L, AgentHostedProfileState.SUSPENDING, 4L,
                AgentHostedProfileState.SUSPENDED, 5L, false)).thenReturn(1);

        beginTransactionSynchronization();
        try {
            AgentHostedProfileEntity result = fixture.transaction.completeUnbind(SCOPE, 12L, 4L, 5L);

            assertEquals(AgentHostedProfileState.SUSPENDED, result.getLifecycleState());
            assertEquals(AgentConstants.BINDING_STATUS_SUSPENDED, binding.getStatus());
            assertEquals(AgentConstants.STATUS_OFFLINE, runtime.getStatus());
            assertEquals("fresh-endpoint", runtime.getEndpoint());
            verify(fixture.eventPublisher, never()).publishAgentStatus(any(), any(), any());
            InOrder order = inOrder(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao);
            order.verify(fixture.bindingDao).findByIdForUpdate(12L);
            order.verify(fixture.hostedDao).findExactForUpdate("owner-a", "client-a", "owner-a", 12L);
            order.verify(fixture.runtimeDao).findByAgentIdForUpdate(AGENT_ID);
            order.verify(fixture.bindingDao).updateById(binding);
            commitSynchronizations();
        } finally {
            clearTransactionSynchronization();
        }

        ArgumentCaptor<AgentRuntimeDTO> snapshot = ArgumentCaptor.forClass(AgentRuntimeDTO.class);
        verify(fixture.eventPublisher, times(1)).publishAgentStatus(
                eq("client-a"), eq("owner-a"), snapshot.capture());
        assertEquals(AGENT_ID, snapshot.getValue().getAgentId());
        assertEquals(AgentConstants.STATUS_OFFLINE, snapshot.getValue().getStatus());
        assertEquals("fresh-endpoint", snapshot.getValue().getEndpoint());
        verify(fixture.apiKeys, never()).update(any());
        verify(fixture.identityService).suspendForBinding("owner-a", "client-a", "owner-a", 12L);
    }

    @Test
    void localUnbindLocksRuntimeBeforeMutationAndNeverUsesPlainRuntimeRead() {
        Fixture fixture = fixture();
        AgentPersonaEntity persona = persona();
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentRuntimeEntity freshRuntime = runtime(AgentConstants.STATUS_BUSY);
        freshRuntime.setCurrentTaskId("fresh-task");
        freshRuntime.setErrorMessage("fresh-error");

        when(fixture.personaDao.findByCode("wuyong")).thenReturn(persona);
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "owner-a", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L))
                .thenReturn(null);
        when(fixture.runtimeDao.findByAgentIdForUpdate(AGENT_ID)).thenReturn(freshRuntime);
        when(fixture.bindingDao.updateById(binding)).thenReturn(1);
        when(fixture.runtimeDao.updateById(freshRuntime)).thenReturn(1);

        beginTransactionSynchronization();
        try {
            assertNull(fixture.transaction.prepareUnbind(SCOPE, "wuyong"));
            assertEquals("fresh-task", freshRuntime.getCurrentTaskId());
            assertEquals("fresh-error", freshRuntime.getErrorMessage());
            assertEquals(AgentConstants.STATUS_OFFLINE, freshRuntime.getStatus());
            verify(fixture.runtimeDao, never()).findByAgentId(any());
            InOrder order = inOrder(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao);
            order.verify(fixture.bindingDao).findExactActiveByScopeAndPersonaForUpdate(
                    "owner-a", "client-a", "owner-a", "wuyong");
            order.verify(fixture.hostedDao).findExactForUpdate("owner-a", "client-a", "owner-a", 12L);
            order.verify(fixture.runtimeDao).findByAgentIdForUpdate(AGENT_ID);
            order.verify(fixture.bindingDao).updateById(binding);
            verify(fixture.eventPublisher, never()).publishAgentStatus(any(), any(), any());
            commitSynchronizations();
        } finally {
            clearTransactionSynchronization();
        }
        verify(fixture.eventPublisher, times(1)).publishAgentStatus(
                eq("client-a"), eq("owner-a"), any(AgentRuntimeDTO.class));
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
        when(fixture.runtimeDao.updateById(runtime)).thenReturn(1);

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

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        AgentRuntimeDao runtimeDao = mock(AgentRuntimeDao.class);
        AgentIdentityService identityService = mock(AgentIdentityService.class);
        AgentPersonaDao personaDao = mock(AgentPersonaDao.class);
        AgentPersonaBindingDao bindingDao = mock(AgentPersonaBindingDao.class);
        AgentHostedProfileDao hostedDao = mock(AgentHostedProfileDao.class);
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        ObjectProvider<ApiKeyService> apiKeyProvider = mock(ObjectProvider.class);
        when(apiKeyProvider.getIfAvailable()).thenReturn(apiKeys);
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        ObjectProvider<AgentEventPublisher> eventPublisherProvider = mock(ObjectProvider.class);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        AgentHostedBindingTransaction transaction = new AgentHostedBindingTransaction(
                runtimeDao, identityService, personaDao, bindingDao, hostedDao, apiKeyProvider,
                eventPublisherProvider, new AgentScopePublicationCoordinator());
        return new Fixture(transaction, runtimeDao, identityService, personaDao, bindingDao,
                hostedDao, apiKeys, eventPublisher);
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
            ApiKeyService apiKeys,
            AgentEventPublisher eventPublisher) {
    }
}
