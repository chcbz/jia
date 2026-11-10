package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
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
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentHostedBindingTransactionTest {
    private static final Scope SCOPE = new Scope("0", "client-a", "owner-a");
    private static final String AGENT_ID = "agt_0123456789abcdef0123456789abcdef";

    @Test
    void retiredUnbindMethodsAreNotAnInternalAlternateLifecycle() {
        var names=java.util.Arrays.stream(AgentHostedBindingTransaction.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        for(String removed:java.util.List.of("resumeRepair","prepareUnbind","completeUnbind","suspendDatabase"))assertFalse(names.contains(removed),removed);
    }

    @Test
    void scopeAllowsTenantOneLocalIdentityButHostedEntryPointsRemainTenantZeroOnly() {
        Scope tenantOne = new Scope("1", "cyf-local", "owner-a");
        assertEquals("1", tenantOne.tenantId());
        assertEquals("cyf-local", tenantOne.clientId());
        assertThrows(IllegalArgumentException.class, tenantOne::requireLegacyHostedTenant);
        assertDoesNotThrow(SCOPE::requireLegacyHostedTenant);
        assertThrows(IllegalArgumentException.class, () -> new Scope("1", "cyf-local", "0"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("1", " cyf-local", "owner-a"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("1", "cyf-local", "owner-a\0"));

        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.transaction.prepareHosted(tenantOne, "wuyong"));
        assertThrows(IllegalArgumentException.class, () -> fixture.transaction.transition(
                tenantOne, 12L, AgentHostedProfileState.PREPARED, 0L,
                AgentHostedProfileState.STAGED_DISABLED, 1L, true));
        assertThrows(IllegalArgumentException.class, () -> fixture.transaction.markRepair(
                tenantOne, 12L, AgentHostedProfileState.PREPARED, null, 0L,
                AgentHostedProfileState.PREPARED, new IllegalStateException("failure")));
        verifyNoInteractions(fixture.personaDao, fixture.bindingDao, fixture.hostedDao,
                fixture.runtimeDao, fixture.identityService, fixture.apiKeys);
    }

    @Test
    void repairUsesExpectedDurableStateCheckpointAndGenerationSoStaleLoserCannotRegressActive() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentHostedProfileEntity winner = hosted(AgentHostedProfileState.ACTIVE, 8L);
        when(fixture.bindingDao.findByIdForUpdate(12L)).thenReturn(binding);
        when(fixture.hostedDao.findExactForUpdate("0", "client-a", "owner-a", 12L))
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
    void existingPrepareRejectsExhaustedGenerationBeforeRuntimeOrHostedMutation() {
        Fixture fixture = fixture();
        AgentPersonaBindingEntity binding = binding();
        AgentIdentityRegistryEntity identity = identity();
        AgentHostedProfileEntity hosted = hosted(AgentHostedProfileState.PREPARED, Long.MAX_VALUE);
        when(fixture.personaDao.findCatalogPersona(SCOPE.tenantId(), SCOPE.clientId(), "wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "0", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "0", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.identityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(fixture.hostedDao.findExactForUpdate("0", "client-a", "owner-a", 12L))
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
    void directAdvanceTransitionRejectsExhaustedGenerationBeforeDatabaseLookup() {
        Fixture fixture = fixture();

        assertThrows(AgentServiceImpl.AgentBizException.class, () -> fixture.transaction.transition(
                SCOPE, 12L, AgentHostedProfileState.PREPARED, Long.MAX_VALUE,
                AgentHostedProfileState.STAGED_DISABLED, Long.MAX_VALUE, true));

        verifyNoInteractions(fixture.bindingDao, fixture.hostedDao, fixture.runtimeDao,
                fixture.apiKeys);
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
        when(fixture.personaDao.findCatalogPersona(SCOPE.tenantId(), SCOPE.clientId(), "wuyong")).thenReturn(persona());
        when(fixture.bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                "0", "client-a", "owner-a", "wuyong")).thenReturn(binding);
        when(fixture.identityService.requireRegistrationIdentityInScope(
                "0", "client-a", "owner-a", AGENT_ID)).thenReturn(identity);
        when(fixture.identityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(fixture.hostedDao.findExactForUpdate("0", "client-a", "owner-a", 12L))
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
        AgentRuntimeEntity foreignTenant = runtime(AgentConstants.STATUS_ONLINE);
        foreignTenant.setTenantId("1");
        detached.setBindingId(null);
        detached.setClientId(null);
        detached.setOwnerJiacn(null);
        detached.setPersonaCode(null);
        detached.setPersonaName(null);

        when(runtimeDao.findByAgentId(AGENT_ID))
                .thenReturn(bound, foreignTenant, detached, detached);

        assertSame(bound, worker.revalidateForPublication(SCOPE, AGENT_ID, 12L));
        assertNull(worker.revalidateForPublication(SCOPE, AGENT_ID, 12L));
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
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        ObjectProvider<ApiKeyService> apiKeyProvider = mock(ObjectProvider.class);
        when(apiKeyProvider.getIfAvailable()).thenReturn(apiKeys);
        AgentHostedBindingTransaction transaction = new AgentHostedBindingTransaction(
                runtimeDao, identityService, personaDao, bindingDao, hostedDao, apiKeyProvider);
        return new Fixture(transaction, runtimeDao, identityService, personaDao, bindingDao,
                hostedDao, apiKeys);
    }

    private static AgentPersonaBindingEntity binding() {
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(12L);
        binding.setTenantId("0");
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
        hosted.setTenantId("0");
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
        runtime.setTenantId("0");
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
        persona.setTenantId(SCOPE.tenantId());
        persona.setClientId(SCOPE.clientId());
        persona.setActive(true);
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

    private record Fixture(AgentHostedBindingTransaction transaction,
            AgentRuntimeDao runtimeDao,
            AgentIdentityService identityService,
            AgentPersonaDao personaDao,
            AgentPersonaBindingDao bindingDao,
            AgentHostedProfileDao hostedDao,
            ApiKeyService apiKeys) {
    }
}
