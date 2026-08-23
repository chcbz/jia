package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.entity.AgentStatsDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskRecommendationDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import com.github.pagehelper.PageInfo;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplTest extends BaseMockTest {
    @Mock
    AgentRuntimeDao agentRuntimeDao;
    @Mock
    AgentIdentityService agentIdentityService;
    @Mock
    AgentPersonaDao agentPersonaDao;
    @Mock
    AgentPersonaBindingDao agentPersonaBindingDao;
    @Mock
    AgentTaskMetaDao agentTaskMetaDao;
    @Mock
    AgentTaskMemberDao agentTaskMemberDao;
    @Mock
    AgentLegacyTaskCompatibilityService legacyTaskCompatibilityService;
    @Mock
    AgentTaskNoteDao agentTaskNoteDao;
    @Mock
    DialogueTemplateDao dialogueTemplateDao;
    @Mock
    ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    @Mock
    ObjectProvider<TaskService> taskServiceProvider;
    @Mock
    ObjectProvider<ApiKeyService> apiKeyServiceProvider;
    @Mock
    ObjectProvider<AgentSceneService> sceneServiceProvider;
    @Mock
    ApiKeyService apiKeyService;
    @Mock
    TaskService taskService;
    @Mock
    AgentEventPublisher eventPublisher;
    @Mock
    AgentSceneService sceneService;
    AgentScopePublicationCoordinator scopePublicationCoordinator;
    AgentServiceImpl agentService;

    @BeforeEach
    void setUpAgentService() {
        EsContextHolder.setContext(new EsContext());
        scopePublicationCoordinator = new AgentScopePublicationCoordinator();
        org.mockito.Mockito.lenient().when(agentPersonaBindingDao.insert(any(AgentPersonaBindingEntity.class)))
                .thenAnswer(invocation -> {
                    AgentPersonaBindingEntity binding = invocation.getArgument(0);
                    if (binding.getId() == null) {
                        binding.setId(1L);
                    }
                    return 1;
                });
        org.mockito.Mockito.lenient().when(agentIdentityService.provisionOpaqueIdentity(
                        any(AgentPersonaBindingEntity.class), any(String.class)))
                .thenAnswer(invocation -> identity(invocation.<AgentPersonaBindingEntity>getArgument(0),
                        AgentConstants.IDENTITY_STATUS_PROVISIONED));
        agentService = new AgentServiceImpl(agentRuntimeDao, agentIdentityService, agentPersonaDao, agentPersonaBindingDao, agentTaskMetaDao,
                agentTaskMemberDao, legacyTaskCompatibilityService, agentTaskNoteDao, dialogueTemplateDao, eventPublisherProvider, taskServiceProvider,
                apiKeyServiceProvider, sceneServiceProvider, scopePublicationCoordinator,
                new AgentSceneFeatureFlags(true, true));
        org.mockito.Mockito.lenient().when(legacyTaskCompatibilityService.resolveAgentIds(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> List.copyOf(invocation.<List<String>>getArgument(3)));
        org.mockito.Mockito.lenient().when(legacyTaskCompatibilityService.resolveAgentId(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        org.mockito.Mockito.lenient().when(legacyTaskCompatibilityService.assignResolved(
                        any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> new AgentLegacyTaskCompatibilityService.AssignOutcome(
                        invocation.getArgument(3), true));
        org.mockito.Mockito.lenient().when(agentRuntimeDao.updateById(any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(agentIdentityService.lockActiveCanonicalAgentIdsInScope(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> List.copyOf(invocation.<List<String>>getArgument(3)));
        org.mockito.Mockito.lenient().when(agentRuntimeDao.findByAgentIdForUpdate(any()))
                .thenAnswer(invocation -> agentRuntimeDao.findByAgentId(invocation.getArgument(0)));
    }

    @Test
    void apiKeyOwnedAgentRequiresMatchingScopeAndActiveBinding() {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId("agent-owned");
        runtime.setClientId("client-a");
        runtime.setOwnerJiacn("tenant-a");
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        runtime.setBindingId(1L);
        when(agentRuntimeDao.findByAgentId("agent-owned")).thenReturn(runtime);
        AgentPersonaBindingEntity active = binding("agent-owned", "wuyong");
        active.setClientId("client-a");
        active.setJiacn("tenant-a");
        AgentIdentityRegistryEntity identity = identity(active, AgentConstants.IDENTITY_STATUS_ACTIVE);
        when(agentIdentityService.requireCanonicalAgentIdInScope(
                "tenant-a", "client-a", "tenant-a", "agent-owned")).thenReturn("agent-owned");
        when(agentIdentityService.requireActiveIdentityForBinding(
                "tenant-a", "client-a", "tenant-a", 1L, "agent-owned")).thenReturn(identity);

        assertEquals("agent-owned", agentService.requireApiKeyOwnedAgent(
                "client-a", "tenant-a", "agent-owned").getAgentId());
    }

    @Test
    void apiKeyOwnedAgentRejectsOtherClientOrTenantWithoutBindingLookup() {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId("agent-other-scope");
        runtime.setClientId("client-a");
        runtime.setOwnerJiacn("tenant-a");
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        when(agentRuntimeDao.findByAgentId("agent-other-scope")).thenReturn(runtime);

        for (String[] scope : List.of(
                new String[]{"client-b", "tenant-a"},
                new String[]{"client-a", "tenant-b"})) {
            AgentServiceImpl.AgentBizException denied = assertThrows(
                    AgentServiceImpl.AgentBizException.class,
                    () -> agentService.requireApiKeyOwnedAgent(
                            scope[0], scope[1], "agent-other-scope"));
            assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, denied.getCode());
        }
        verify(agentPersonaBindingDao, never())
                .findActiveByClientJiacnAndAgentId(any(), any(), any());
    }

    @Test
    void apiKeyOwnedAgentRejectsSystemAgentBeforeRuntimeLookup() {
        AgentServiceImpl.AgentBizException denied = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.requireApiKeyOwnedAgent(
                        "client-a", "tenant-a", AgentConstants.BUILTIN_SONGJIANG_AGENT_ID));

        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, denied.getCode());
        verify(agentRuntimeDao, never()).findByAgentId(any());
        verify(agentPersonaBindingDao, never())
                .findActiveByClientJiacnAndAgentId(any(), any(), any());
    }

    @Test
    void apiKeyOwnedAgentRejectsMissingOrInactiveBinding() {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId("agent-unbound");
        runtime.setClientId("client-a");
        runtime.setOwnerJiacn("tenant-a");
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        runtime.setBindingId(1L);
        when(agentRuntimeDao.findByAgentId("agent-unbound")).thenReturn(runtime);
        when(agentIdentityService.requireActiveIdentityForBinding(
                "tenant-a", "client-a", "tenant-a", 1L, "agent-unbound"))
                .thenThrow(new AgentServiceImpl.AgentBizException(
                        AgentErrorConstants.AGENT_FORBIDDEN, "binding inactive"));

        AgentServiceImpl.AgentBizException denied = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.requireApiKeyOwnedAgent(
                        "client-a", "tenant-a", "agent-unbound"));
        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, denied.getCode());
    }

    @Test
    void registerCreatesOnlineAgentAndPublishesStatus() {
        AgentPersonaBindingEntity binding = binding("agent-001", "wuyong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_PROVISIONED);
        AgentPersonaEntity persona = persona("wuyong", "吴用", "智多星");
        when(agentIdentityService.requireRegistrationIdentityInScope(
                "juyiting", "jia_client", "juyiting", "agent-001")).thenReturn(identity);
        when(agentIdentityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(agentIdentityService.activateForFirstRegistration(identity)).thenAnswer(invocation -> {
            identity.setLifecycleStatus(AgentConstants.IDENTITY_STATUS_ACTIVE);
            return identity;
        });
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(null);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId("agent-001");
        request.setAbilities(List.of("planning", "research"));
        request.setEndpoint("wss://example.com/openclaw/agent-001");

        AgentRegisterResultDTO result = agentService.register(request);

        assertEquals("agent-001", result.getAgentId());
        assertEquals(AgentConstants.STATUS_ONLINE, result.getStatus());
        assertNotNull(result.getToken());

        ArgumentCaptor<AgentRuntimeEntity> entityCaptor = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao).insert(entityCaptor.capture());
        AgentRuntimeEntity saved = entityCaptor.getValue();
        assertEquals("agent-001", saved.getAgentId());
        assertEquals("吴用", saved.getName());
        assertEquals("wuyong", saved.getPersonaCode());
        assertEquals("juyiting", saved.getOwnerJiacn());
        assertEquals("jia_client", saved.getClientId());
        assertEquals(AgentConstants.STATUS_ONLINE, saved.getStatus());
        assertNotNull(saved.getLastSeenAt());
        assertNotNull(saved.getTokenHash());

        ArgumentCaptor<AgentRuntimeDTO> eventCaptor = ArgumentCaptor.forClass(AgentRuntimeDTO.class);
        verify(eventPublisher).publishAgentStatus(eq("jia_client"), eq("juyiting"), eventCaptor.capture());
        assertEquals("agent-001", eventCaptor.getValue().getAgentId());
        assertEquals(AgentConstants.STATUS_ONLINE, eventCaptor.getValue().getStatus());
    }

    @Test
    void registerUsesClientReportedAbilitiesInsteadOfPersonaDefaults() {
        AgentPersonaBindingEntity binding = binding("agent-001", "wuyong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_PROVISIONED);
        AgentPersonaEntity persona = persona("wuyong", "吴用", "智多星");
        when(agentIdentityService.requireRegistrationIdentityInScope(
                "juyiting", "jia_client", "juyiting", "agent-001")).thenReturn(identity);
        when(agentIdentityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(agentIdentityService.activateForFirstRegistration(identity)).thenReturn(identity);
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(null);

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId("agent-001");
        request.setAbilities(List.of(" code-edit ", "debug", "DEBUG"));

        agentService.register(request);

        ArgumentCaptor<AgentRuntimeEntity> captor = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao).insert(captor.capture());
        assertEquals("[\"code-edit\",\"debug\"]", captor.getValue().getAbilities());
    }

    @Test
    void firstRegistrationWithoutAbilitiesFallsBackToPersonaDefaults() {
        AgentPersonaBindingEntity binding = binding("agent-001", "wuyong");
        AgentIdentityRegistryEntity identity = identity(
                binding, AgentConstants.IDENTITY_STATUS_PROVISIONED);
        AgentPersonaEntity persona = persona("wuyong", "吴用", "智多星");
        persona.setAbilities("[\"planning\",\"research\"]");
        when(agentIdentityService.requireRegistrationIdentityInScope(
                "juyiting", "jia_client", "juyiting", "agent-001")).thenReturn(identity);
        when(agentIdentityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(agentIdentityService.activateForFirstRegistration(identity)).thenReturn(identity);
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(null);

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId("agent-001");
        agentService.register(request);

        ArgumentCaptor<AgentRuntimeEntity> captor = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
        verify(agentRuntimeDao).insert(captor.capture());
        assertEquals("[\"planning\",\"research\"]", captor.getValue().getAbilities());
    }

    @Test
    void registerWithoutAbilitiesPreservesLockedCurrentRuntimeSnapshot() {
        AgentPersonaBindingEntity binding = binding("agent-001", "wuyong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_ACTIVE);
        AgentPersonaEntity persona = persona("wuyong", "吴用", "智多星");
        AgentRuntimeEntity stale = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_OFFLINE, "[\"stale-skill\"]");
        stale.setId(98L);
        stale.setPersonaCode("wuyong");
        AgentRuntimeEntity lockedCurrent = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_OFFLINE, "[\"current-skill\"]");
        lockedCurrent.setId(99L);
        lockedCurrent.setPersonaCode("wuyong");
        when(agentIdentityService.requireRegistrationIdentityInScope(
                "juyiting", "jia_client", "juyiting", "agent-001")).thenReturn(identity);
        when(agentIdentityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(agentIdentityService.activateForFirstRegistration(identity)).thenReturn(identity);
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(stale);
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-001")).thenReturn(lockedCurrent);

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId("agent-001");
        agentService.register(request);

        assertEquals("[\"current-skill\"]", lockedCurrent.getAbilities());
        assertEquals("[\"stale-skill\"]", stale.getAbilities());
        verify(agentRuntimeDao, never()).findByAgentId("agent-001");
        verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
        verify(agentRuntimeDao).updateById(lockedCurrent);
        org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
                agentIdentityService, agentRuntimeDao);
        lockOrder.verify(agentIdentityService).activateForFirstRegistration(identity);
        lockOrder.verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
    }

    @Test
    void unbindUpdatesOnlyLockedCurrentRuntimeAndPreservesAbilities() {
        String personaCode = "review-unbind-lock-20260823";
        AgentPersonaBindingEntity binding = binding("agent-001", personaCode);
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_ACTIVE);
        AgentPersonaEntity persona = persona(personaCode, "Review Agent", "Reviewer");
        AgentRuntimeEntity stale = ownedAgent(
                "agent-001", "Review Agent", AgentConstants.STATUS_ONLINE, "[\"stale-skill\"]");
        AgentRuntimeEntity lockedCurrent = ownedAgent(
                "agent-001", "Review Agent", AgentConstants.STATUS_ONLINE, "[\"fresh-skill\"]");
        when(agentPersonaDao.findByCode(personaCode)).thenReturn(persona);
        when(agentPersonaBindingDao.findActiveByClientJiacnAndPersona(
                "jia_client", "juyiting", personaCode)).thenReturn(binding);
        when(agentIdentityService.requireRegistrationIdentityInScope(
                "juyiting", "jia_client", "juyiting", "agent-001")).thenReturn(identity);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(stale);
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-001")).thenReturn(lockedCurrent);

        agentService.unbindPersona(personaCode);

        assertEquals(AgentConstants.STATUS_OFFLINE, lockedCurrent.getStatus());
        assertEquals("[\"fresh-skill\"]", lockedCurrent.getAbilities());
        assertEquals(AgentConstants.STATUS_ONLINE, stale.getStatus());
        verify(agentIdentityService).suspendForBinding(
                "juyiting", "jia_client", "juyiting", binding.getId());
        verify(agentRuntimeDao, never()).findByAgentId("agent-001");
        verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
        verify(agentRuntimeDao).updateById(lockedCurrent);
        org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
                agentIdentityService, agentRuntimeDao);
        lockOrder.verify(agentIdentityService).suspendForBinding(
                "juyiting", "jia_client", "juyiting", binding.getId());
        lockOrder.verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
    }

    @Test
    void httpAbilityDtosDeserializeMissingAndEmpty() throws Exception {
        AgentRegisterDTO missingRegister = JsonUtil.getMapper().readValue(
                "{\"agentId\":\"agent-001\"}", AgentRegisterDTO.class);
        AgentRegisterDTO emptyRegister = JsonUtil.getMapper().readValue(
                "{\"agentId\":\"agent-001\",\"abilities\":[]}", AgentRegisterDTO.class);
        AgentStatusDTO missingStatus = JsonUtil.getMapper().readValue(
                "{\"status\":\"online\"}", AgentStatusDTO.class);
        AgentStatusDTO emptyStatus = JsonUtil.getMapper().readValue(
                "{\"status\":\"online\",\"abilities\":[]}", AgentStatusDTO.class);

        assertNull(missingRegister.getAbilities());
        assertTrue(emptyRegister.getAbilities().isEmpty());
        assertNull(missingStatus.getAbilities());
        assertTrue(emptyStatus.getAbilities().isEmpty());
    }

    @Test
    void presenceRefreshesRuntimeAbilities() {
        AgentRuntimeEntity existing = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_ONLINE, "[\"old-skill\"]");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(existing);

        AgentStatusDTO status = new AgentStatusDTO();
        status.setStatus(AgentConstants.STATUS_ONLINE);
        status.setAbilities(List.of("review", "test"));

        AgentRuntimeDTO result = agentService.updateStatus("agent-001", status);

        assertEquals("[\"review\",\"test\"]", existing.getAbilities());
        assertEquals(List.of("review", "test"), result.getAbilities());
        verify(agentRuntimeDao).updateById(existing);
    }

    @Test
    void presencePreservesAbilitiesWhenMissingAndClearsThemWhenExplicitlyEmpty() {
        AgentRuntimeEntity existing = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_ONLINE, "[\"old-skill\"]");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(existing);

        AgentStatusDTO missing = new AgentStatusDTO();
        missing.setStatus(AgentConstants.STATUS_ONLINE);
        assertEquals(List.of("old-skill"), agentService.updateStatus("agent-001", missing).getAbilities());

        AgentStatusDTO clear = new AgentStatusDTO();
        clear.setStatus(AgentConstants.STATUS_ONLINE);
        clear.setAbilities(List.of());
        assertTrue(agentService.updateStatus("agent-001", clear).getAbilities().isEmpty());
        assertEquals("[]", existing.getAbilities());
    }

    @Test
    void presenceStatusAndCapabilitiesPublishOnlyAfterCommit() {
        AgentRuntimeEntity existing = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(existing);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        AgentStatusDTO status = new AgentStatusDTO();
        status.setStatus(AgentConstants.STATUS_ONLINE);
        status.setAbilities(List.of("review"));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            agentService.updateStatus("agent-001", status);
            verify(agentRuntimeDao, never()).findRosterByOwner(
                    "jia_client", "juyiting", null, null);
            verify(eventPublisher, never()).publishAgentStatus(any(), any(), any());
            verify(eventPublisher, never()).publishCapabilityIndex(any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(agentRuntimeDao).findRosterByOwner(
                    "jia_client", "juyiting", null, null);
            verify(eventPublisher).publishAgentStatus(
                    eq("jia_client"), eq("juyiting"), any(AgentRuntimeDTO.class));
            verify(eventPublisher).publishCapabilityIndex(
                    eq("jia_client"), eq("juyiting"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void runtimeAbilitySnapshotRejectsControlCharactersAndOversizedInputs() {
        AgentRuntimeEntity existing = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_ONLINE, "[\"old-skill\"]");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(existing);

        AgentStatusDTO c1 = new AgentStatusDTO();
        c1.setAbilities(List.of("review" + (char) 0x85));
        IllegalArgumentException controlFailure = assertThrows(
                IllegalArgumentException.class,
                () -> agentService.updateStatus("agent-001", c1));
        assertEquals("ability must not contain control characters", controlFailure.getMessage());

        AgentStatusDTO tooMany = new AgentStatusDTO();
        tooMany.setAbilities(java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> "ability-" + index)
                .toList());
        IllegalArgumentException countFailure = assertThrows(
                IllegalArgumentException.class,
                () -> agentService.updateStatus("agent-001", tooMany));
        assertEquals("abilities must contain at most 128 items", countFailure.getMessage());

        AgentStatusDTO tooLong = new AgentStatusDTO();
        tooLong.setAbilities(List.of("x".repeat(101)));
        IllegalArgumentException lengthFailure = assertThrows(
                IllegalArgumentException.class,
                () -> agentService.updateStatus("agent-001", tooLong));
        assertEquals("ability must contain at most 100 characters", lengthFailure.getMessage());

        verify(agentRuntimeDao, never()).updateById(existing);
    }

    @Test
    void runtimeAbilitySnapshotAcceptsExactly128ItemsAnd100Characters() {
        AgentRuntimeEntity existing = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_ONLINE, "[]");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(existing);
        List<String> abilities = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 127)
                .mapToObj(index -> "ability-" + index)
                .toList());
        abilities.add("x".repeat(100));
        AgentStatusDTO status = new AgentStatusDTO();
        status.setAbilities(abilities);

        AgentRuntimeDTO result = agentService.updateStatus("agent-001", status);

        assertEquals(128, result.getAbilities().size());
        assertEquals(100, result.getAbilities().get(result.getAbilities().size() - 1).length());
        verify(agentRuntimeDao).updateById(existing);
    }

    @Test
    void createTaskCreatesTaskPlanAndOpenAgentTaskMeta() {
        when(taskServiceProvider.getIfAvailable()).thenReturn(taskService);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("Inspect archive search with a title that exceeds task plan name");
        request.setDescription("Verify library retrieval");
        request.setRequiredAbilities(List.of("research", "debug"));
        request.setReward(30);

        AgentTaskDTO result = agentService.createTask(request);

        assertEquals(AgentConstants.TASK_STATUS_OPEN, result.getStatus());
        assertEquals("Inspect archive search with a title that exceeds task plan name", result.getTitle());
        assertEquals(List.of("research", "debug"), result.getRequiredAbilities());

        ArgumentCaptor<TaskPlanEntity> planCaptor = ArgumentCaptor.forClass(TaskPlanEntity.class);
        verify(taskService).create(planCaptor.capture());
        assertEquals(30, planCaptor.getValue().getName().length());
        assertEquals("Inspect archive search with a ", planCaptor.getValue().getName());
        assertEquals("Verify library retrieval", planCaptor.getValue().getDescription());
        assertEquals("juyiting", planCaptor.getValue().getJiacn());

        ArgumentCaptor<AgentTaskMetaEntity> metaCaptor = ArgumentCaptor.forClass(AgentTaskMetaEntity.class);
        verify(agentTaskMetaDao).insert(metaCaptor.capture());
        assertEquals(AgentConstants.TASK_STATUS_OPEN, metaCaptor.getValue().getRewardStatus());
        assertEquals("juyiting", metaCaptor.getValue().getTenantId());
        assertEquals("jia_client", metaCaptor.getValue().getClientId());
        ArgumentCaptor<AgentTaskDTO> eventTaskCaptor = ArgumentCaptor.forClass(AgentTaskDTO.class);
        verify(eventPublisher).publishTaskEvent(eq("task_created"), eventTaskCaptor.capture());
        assertEquals("juyiting", eventTaskCaptor.getValue().getTenantId());
        assertEquals("jia_client", eventTaskCaptor.getValue().getClientId());
    }

    @Test
    void resolvesScopedTaskMembersAndUsesLegacyAssigneesOnlyWhenMemberRowsAreAbsent() {
        AgentTaskMemberEntity active = taskMember(
                "tenant-a", "client-a", "task-1", "agent-active", "working");
        AgentTaskMemberEntity left = taskMember(
                "tenant-a", "client-a", "task-1", "agent-left", "left");
        when(agentTaskMemberDao.listByTask("tenant-a", "client-a", "task-1"))
                .thenReturn(List.of(active, left));

        assertEquals(List.of("agent-active"),
                agentService.listTaskMemberAgentIds("tenant-a", "client-a", "task-1"));
        verify(agentTaskMetaDao, never()).findByTaskId("tenant-a", "client-a", "task-1");

        AgentTaskMetaEntity legacy = new AgentTaskMetaEntity();
        legacy.setAssignedAgentId("[\"agent-one\",\"agent-two\"]");
        when(agentTaskMemberDao.listByTask("tenant-a", "client-a", "task-legacy")).thenReturn(List.of());
        when(agentTaskMetaDao.findByTaskId("tenant-a", "client-a", "task-legacy")).thenReturn(legacy);

        assertEquals(List.of("agent-one", "agent-two"),
                agentService.listTaskMemberAgentIds("tenant-a", "client-a", "task-legacy"));
    }

    @Test
    void assignTaskAcceptsMultipleAgentsAndReturnsAssignees() {
        AgentRuntimeEntity wuYong = new AgentRuntimeEntity();
        wuYong.setAgentId("agent-wuyong");
        wuYong.setName("Wu Yong");
        wuYong.setStatus(AgentConstants.STATUS_ONLINE);
        wuYong.setAbilities("[\"planning\"]");
        markOwned(wuYong);

        AgentRuntimeEntity linChong = new AgentRuntimeEntity();
        linChong.setAgentId("agent-linchong");
        linChong.setName("Lin Chong");
        linChong.setStatus(AgentConstants.STATUS_ONLINE);
        linChong.setAbilities("[\"execute\"]");
        markOwned(linChong);

        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(wuYong);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(linChong);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);

        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentIds(List.of("agent-wuyong", "agent-linchong"));

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        assertEquals(AgentConstants.TASK_STATUS_ASSIGNED, result.getStatus());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), result.getAssignedAgentIds());
        assertEquals(2, result.getAssignees().size());
        assertEquals("Wu Yong", result.getAssignees().get(0).getAgentName());

        verify(agentTaskMetaDao, never()).updateById(meta);
        verify(legacyTaskCompatibilityService).assignResolved(
                "juyiting", "jia_client", "task-001",
                List.of("agent-wuyong", "agent-linchong"), false);
        verify(eventPublisher).publishTaskEvent(eq("task_assigned"), any(AgentTaskDTO.class));
    }

    @Test
    void assignTaskPublishesSemanticBountyMovementWithExpiry() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        agent.setPersonaCode("wuyong");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        agentService.assignTask("task-001", request);

        ArgumentCaptor<AgentSceneStateDTO> stateCaptor = ArgumentCaptor.forClass(AgentSceneStateDTO.class);
        verify(sceneService).upsertState(eq("juyiting-main"), stateCaptor.capture());
        AgentSceneStateDTO state = stateCaptor.getValue();
        assertEquals("agent-wuyong", state.getAgentId());
        assertEquals("wuyong", state.getPersonaCode());
        assertEquals("moving_to_bounty", state.getBehavior());
        assertEquals("bounty-board", state.getTargetRegionId());
        assertEquals("task", state.getRelatedType());
        assertEquals("task-001", state.getRelatedId());
        assertEquals("moving", state.getPhase());
        assertNotNull(state.getStartedAt());
        assertTrue(state.getExpectedArrivalAt() > state.getStartedAt());
        assertTrue(state.getExpiresAt() > state.getExpectedArrivalAt());
    }

    @Test
    void sceneStateDisabledPreservesTaskAssignmentWithoutSceneWrite() {
        agentService = new AgentServiceImpl(agentRuntimeDao, agentIdentityService, agentPersonaDao, agentPersonaBindingDao, agentTaskMetaDao,
                agentTaskMemberDao, legacyTaskCompatibilityService, agentTaskNoteDao, dialogueTemplateDao, eventPublisherProvider, taskServiceProvider,
                apiKeyServiceProvider, sceneServiceProvider, scopePublicationCoordinator,
                new AgentSceneFeatureFlags(false, true));
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        agent.setPersonaCode("wuyong");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        assertEquals(AgentConstants.TASK_STATUS_ASSIGNED, result.getStatus());
        verify(sceneService, never()).upsertState(any(), any());
        verify(sceneServiceProvider, never()).getIfAvailable();
    }

    @Test
    void dialoguePublishesSemanticDiscussionMovementForMatchingRosterAgent() {
        AgentRuntimeEntity agent = runtimeAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        agent.setPersonaCode("wuyong");
        agent.setPersonaName("Wu Yong");
        when(agentPersonaDao.findByName("Wu Yong")).thenReturn(persona("wuyong", "Wu Yong", "Strategist"));
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null))
                .thenReturn(List.of(agent));
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        DialogueTemplateEntity template = new DialogueTemplateEntity();
        template.setContent("Discuss the plan");
        when(dialogueTemplateDao.findByPersonaAndType("Wu Yong", "DISCUSSION"))
                .thenReturn(List.of(template));
        DialogueRequestDTO request = new DialogueRequestDTO();
        request.setPersonaName("Wu Yong");
        request.setDialogueType("DISCUSSION");

        assertEquals("Discuss the plan", agentService.generateDialogue(request));

        ArgumentCaptor<AgentSceneStateDTO> stateCaptor = ArgumentCaptor.forClass(AgentSceneStateDTO.class);
        verify(sceneService).upsertState(eq("juyiting-main"), stateCaptor.capture());
        AgentSceneStateDTO state = stateCaptor.getValue();
        assertEquals("agent-wuyong", state.getAgentId());
        assertEquals("moving_to_discussion", state.getBehavior());
        assertEquals("council-table", state.getTargetRegionId());
        assertEquals("discussion", state.getRelatedType());
        assertTrue(state.getRelatedId().matches("dlg-[0-9a-f]{32}"));
        assertTrue(state.getRelatedId().length() <= 40);
        assertTrue(state.getExpiresAt() > state.getExpectedArrivalAt());
    }

    @Test
    void chatPublishesAllowlistedSemanticTypeWithOpaqueBoundedRelatedId() {
        AgentRuntimeEntity agent = runtimeAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        agent.setPersonaCode("wuyong");
        when(agentPersonaDao.findByName("Wu Yong")).thenReturn(persona("wuyong", "Wu Yong", "Strategist"));
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null))
                .thenReturn(List.of(agent));
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        when(dialogueTemplateDao.findByPersonaAndType("Wu Yong", "CHAT")).thenReturn(List.of());
        DialogueRequestDTO request = new DialogueRequestDTO();
        request.setPersonaName("Wu Yong");
        request.setDialogueType("CHAT");

        assertEquals("今日聚义厅中，正好议事。", agentService.generateDialogue(request));

        ArgumentCaptor<AgentSceneStateDTO> stateCaptor = ArgumentCaptor.forClass(AgentSceneStateDTO.class);
        verify(sceneService).upsertState(eq("juyiting-main"), stateCaptor.capture());
        AgentSceneStateDTO state = stateCaptor.getValue();
        assertEquals("chat", state.getRelatedType());
        assertTrue(state.getRelatedId().matches("dlg-[0-9a-f]{32}"));
        assertTrue(state.getRelatedId().length() <= 40);
    }

    @Test
    void rawOrExtendedDialogueTypeIsNeverPersistedAsSceneMetadata() {
        String sensitiveType = "DISCUSSION-secret-token-" + "x".repeat(500);
        DialogueTemplateEntity template = new DialogueTemplateEntity();
        template.setContent("Core dialogue still succeeds");
        when(dialogueTemplateDao.findByPersonaAndType("Wu Yong", sensitiveType)).thenReturn(List.of(template));
        DialogueRequestDTO request = new DialogueRequestDTO();
        request.setPersonaName("Wu Yong");
        request.setDialogueType(sensitiveType);

        assertEquals("Core dialogue still succeeds", agentService.generateDialogue(request));

        verify(sceneService, never()).upsertState(any(), any());
    }

    @Test
    void ambiguousDiscussionPersonaSkipsScenePublicationWithoutFailingDialogue() {
        AgentPersonaEntity persona = persona("wuyong", "Wu Yong", "Strategist");
        when(agentPersonaDao.findByName("Wu Yong")).thenReturn(persona);
        AgentRuntimeEntity first = runtimeAgent("agent-wuyong-1", "Wu Yong 1", AgentConstants.STATUS_ONLINE, "[]");
        first.setPersonaCode("wuyong");
        AgentRuntimeEntity second = runtimeAgent("agent-wuyong-2", "Wu Yong 2", AgentConstants.STATUS_ONLINE, "[]");
        second.setPersonaCode("wuyong");
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null))
                .thenReturn(List.of(first, second));
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        DialogueTemplateEntity template = new DialogueTemplateEntity();
        template.setContent("Ambiguous roster dialogue");
        when(dialogueTemplateDao.findByPersonaAndType("Wu Yong", "DISCUSSION")).thenReturn(List.of(template));
        DialogueRequestDTO request = new DialogueRequestDTO();
        request.setPersonaName("Wu Yong");
        request.setDialogueType("DISCUSSION");

        assertEquals("Ambiguous roster dialogue", agentService.generateDialogue(request));

        verify(sceneService, never()).upsertState(any(), any());
    }

    @Test
    void scenePublicationFailureNeverFailsCoreTaskAssignment() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        agent.setPersonaCode("wuyong");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        doThrow(new IllegalStateException("scene unavailable"))
                .when(sceneService).upsertState(any(), any());
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AgentTaskDTO result = agentService.assignTask("task-001", request);

            assertEquals(AgentConstants.TASK_STATUS_ASSIGNED, result.getStatus());
            verify(legacyTaskCompatibilityService).assignResolved(
                    "juyiting", "jia_client", "task-001", List.of("agent-wuyong"), false);
            verify(sceneService, never()).upsertState(any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());
            verify(sceneService).upsertState(any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void completedTaskPublishesReturnHomeForEachAssignedAgent() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        meta.setTaskVersion(1L);
        meta.setAssignedAgentId("agent-wuyong");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);
        when(legacyTaskCompatibilityService.reportResolved(
                "juyiting", "jia_client", "task-001", "agent-wuyong",
                AgentConstants.TASK_STATUS_COMPLETED, null))
                .thenReturn(new AgentLegacyTaskCompatibilityService.ReportOutcome(
                        AgentConstants.TASK_STATUS_COMPLETED, 1L, true, true,
                        "agent-wuyong", List.of("agent-wuyong")));
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_BUSY, "[\"planning\"]");
        agent.setPersonaCode("wuyong");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-wuyong");
        request.setStatus(AgentConstants.TASK_STATUS_COMPLETED);

        agentService.reportTask("task-001", request);

        ArgumentCaptor<AgentSceneStateDTO> stateCaptor = ArgumentCaptor.forClass(AgentSceneStateDTO.class);
        verify(sceneService).upsertState(eq("juyiting-main"), stateCaptor.capture());
        AgentSceneStateDTO state = stateCaptor.getValue();
        assertEquals("agent-wuyong", state.getAgentId());
        assertEquals("returning_home", state.getBehavior());
        assertEquals("main-seat", state.getTargetRegionId());
        assertEquals("task", state.getRelatedType());
        assertEquals("task-001", state.getRelatedId());
        assertEquals("moving", state.getPhase());
    }

    @Test
    void assignTaskCreatesTaskBriefingActionForEachAssigneeAndExposesDispatchResults() {
        AgentRuntimeEntity wuYong = new AgentRuntimeEntity();
        wuYong.setAgentId("agent-wuyong");
        wuYong.setName("Wu Yong");
        wuYong.setStatus(AgentConstants.STATUS_ONLINE);
        markOwned(wuYong);

        AgentRuntimeEntity linChong = new AgentRuntimeEntity();
        linChong.setAgentId("agent-linchong");
        linChong.setName("Lin Chong");
        linChong.setStatus(AgentConstants.STATUS_ONLINE);
        markOwned(linChong);

        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(wuYong);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(linChong);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.publishAgentAction(any(AgentActionIntentDTO.class))).thenAnswer(invocation -> {
            AgentActionIntentDTO intent = invocation.getArgument(0);
            AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
            result.setIntentId(intent.getIntentId());
            result.setTaskId(intent.getTaskId());
            result.setTargetAgentId(intent.getActorAgentId());
            result.setStatus("dispatched");
            return result;
        });

        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);

        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentIds(List.of("agent-wuyong", "agent-linchong", "agent-wuyong"));

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        ArgumentCaptor<AgentActionIntentDTO> intentCaptor = ArgumentCaptor.forClass(AgentActionIntentDTO.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishAgentAction(intentCaptor.capture());
        List<AgentActionIntentDTO> intents = intentCaptor.getAllValues();
        assertEquals(List.of("agent-wuyong", "agent-linchong"), intents.stream().map(AgentActionIntentDTO::getActorAgentId).toList());
        assertTrue(intents.stream().allMatch(intent -> "task_briefing".equals(intent.getActionType())));
        assertTrue(intents.stream().allMatch(intent -> intent.getIntentId().equals(intent.getCommandId())));
        assertTrue(intents.stream().allMatch(intent -> AgentProtocolConstants.COMMAND_TASK_INVITE.equals(intent.getCommandType())));
        assertTrue(intents.stream().allMatch(intent -> "task-001".equals(intent.getCorrelationId())));
        assertTrue(intents.stream().allMatch(intent -> "juyiting".equals(intent.getTenantId())));
        assertTrue(intents.stream().allMatch(intent -> "jia_client".equals(intent.getClientId())));
        assertTrue(intents.stream().allMatch(intent -> "juyiting".equals(intent.getConversationType())));
        assertTrue(intents.stream().allMatch(intent -> !intent.getRequiresApproval()));
        assertEquals(2, result.getActionDispatchResults().size());
        assertTrue(result.getActionDispatchResults().stream().allMatch(dispatch -> "dispatched".equals(dispatch.getStatus())));
    }

    @Test
    void assignTaskQueuesOfflineAgentWhenQueueIsAllowed() {
        AgentRuntimeEntity offlineAgent = new AgentRuntimeEntity();
        offlineAgent.setAgentId("agent-offline");
        offlineAgent.setName("Offline Agent");
        offlineAgent.setStatus(AgentConstants.STATUS_OFFLINE);
        markOwned(offlineAgent);
        when(agentRuntimeDao.findByAgentId("agent-offline")).thenReturn(offlineAgent);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);

        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-offline");
        request.setAllowQueue(true);

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        verify(eventPublisher, never()).publishAgentAction(any(AgentActionIntentDTO.class));
        assertEquals(1, result.getActionDispatchResults().size());
        AgentActionDispatchResultDTO dispatch = result.getActionDispatchResults().getFirst();
        assertEquals("agent-offline", dispatch.getTargetAgentId());
        assertEquals("queued", dispatch.getStatus());
        assertEquals("Agent offline or not connected", dispatch.getMessage());
    }

    @Test
    void listCapabilitiesIncludesOwnedAgentsAndSongjiangLeaderProfile() {
        AgentRuntimeEntity wuYong = new AgentRuntimeEntity();
        wuYong.setAgentId("agent-wuyong");
        wuYong.setName("吴用");
        wuYong.setPersonaCode("wuyong");
        wuYong.setStatus(AgentConstants.STATUS_ONLINE);
        wuYong.setAbilities("[\"planning\",\"analysis\"]");
        wuYong.setClientId("jia_client");
        wuYong.setOwnerJiacn("juyiting");

        AgentPersonaEntity songjiang = persona("songjiang", "宋江", "及时雨");
        songjiang.setSystemAgent(true);
        songjiang.setAbilities("[\"coordination\",\"dispatch\",\"planning\",\"briefing\"]");

        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null)).thenReturn(List.of(wuYong));
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona("wuyong", "吴用", "智多星"));
        when(agentPersonaDao.findByCode(AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE)).thenReturn(songjiang);

        var result = agentService.listCapabilities();

        assertEquals(2, result.size());
        assertEquals("agent-wuyong", result.get(0).getAgentId());
        assertTrue(result.get(0).getRoles().contains("planner"));
        assertEquals(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, result.get(1).getAgentId());
        assertTrue(result.get(1).getRoles().contains("leader"));
        assertEquals("宋江首领负责议事、拆解、派令、追踪和复盘。", result.get(1).getCollaborationHint());
    }


    @Test
    void personaCatalogDoesNotResolveOtherOwnerBindingThroughCurrentScope() {
        AgentPersonaEntity persona = persona("lujunyi", "卢俊义", "玉麒麟");
        AgentPersonaBindingEntity otherOwnerBinding = binding("jyt-jiafewnnv58ec2379c-lujunyi", "lujunyi");
        otherOwnerBinding.setJiacn("other-owner");
        otherOwnerBinding.setTenantId("other-owner");

        when(agentPersonaDao.selectAll()).thenReturn(List.of(persona));
        when(agentPersonaBindingDao.findActiveByClientAndPersona("jia_client", "lujunyi"))
                .thenReturn(otherOwnerBinding);

        List<AgentRuntimeDTO> result = agentService.listPersonaCatalog();

        assertEquals(1, result.size());
        AgentRuntimeDTO dto = result.getFirst();
        assertEquals("lujunyi", dto.getPersonaCode());
        assertEquals(null, dto.getAgentId());
        assertEquals("other-owner", dto.getOwnerJiacn());
        assertEquals(true, dto.getBound());
        assertEquals(false, dto.getBoundToMe());
        assertEquals(false, dto.getCanBind());
        assertEquals(AgentConstants.STATUS_OFFLINE, dto.getStatus());
        verify(agentIdentityService, never()).requireRegistrationIdentityInScope(any(), any(), any(), any());
        verify(agentRuntimeDao, never()).findByAgentId(any());
    }

    @Test
    void newPersonaBindingIssuesOpaqueIdentityInsteadOfLegacyJytId() {
        AgentPersonaEntity persona = persona("wuyong", "吴用", "智多星");
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona);

        AgentRuntimeDTO result = agentService.bindPersona("wuyong");

        assertTrue(result.getAgentId().matches("agt_[0-9a-f]{32}"));
        assertTrue(!result.getAgentId().startsWith("jyt-"));
        ArgumentCaptor<AgentPersonaBindingEntity> bindingCaptor =
                ArgumentCaptor.forClass(AgentPersonaBindingEntity.class);
        verify(agentPersonaBindingDao).insert(bindingCaptor.capture());
        AgentPersonaBindingEntity binding = bindingCaptor.getValue();
        assertEquals("juyiting", binding.getTenantId());
        assertEquals("juyiting", binding.getJiacn());
        assertEquals("jia_client", binding.getClientId());
        assertEquals(result.getAgentId(), binding.getAgentId());
        verify(agentIdentityService).provisionOpaqueIdentity(
                eq(binding), eq("A08 persona bind: wuyong"));
    }

    @Test
    void legacyAliasRegistrationReturnsCanonicalIdAndPersistsCanonicalRuntime() {
        String legacy = "jyt-client-a-wuyong";
        String canonical = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        AgentPersonaBindingEntity binding = binding(legacy, "wuyong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_PROVISIONED);
        identity.setCanonicalAgentId(canonical);
        identity.setCanonicalType(AgentConstants.IDENTITY_TYPE_OPAQUE);
        when(agentIdentityService.requireRegistrationIdentityInScope(
                "juyiting", "jia_client", "juyiting", legacy)).thenReturn(identity);
        when(agentIdentityService.requireActiveBinding(identity, legacy)).thenReturn(binding);
        when(agentIdentityService.activateForFirstRegistration(identity)).thenAnswer(invocation -> {
            identity.setLifecycleStatus(AgentConstants.IDENTITY_STATUS_ACTIVE);
            return identity;
        });
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona("wuyong", "吴用", "智多星"));

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId(legacy);
        AgentRegisterResultDTO result = agentService.register(request);

        assertEquals(canonical, result.getAgentId());
        ArgumentCaptor<AgentRuntimeEntity> runtimeCaptor = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao).insert(runtimeCaptor.capture());
        assertEquals(canonical, runtimeCaptor.getValue().getAgentId());
        assertEquals(binding.getId(), runtimeCaptor.getValue().getBindingId());
    }

    @Test
    void localPersonaBindingReturnsApiKeyForAgentSetup() {
        AgentPersonaEntity persona = persona("husanniang", "扈三娘", "一丈青");
        OauthApiKeyEntity apiKey = new OauthApiKeyEntity();
        apiKey.setApiKey("cdx_test_key");
        apiKey.setStatus(1);

        when(agentPersonaDao.findByCode("husanniang")).thenReturn(persona);
        when(apiKeyServiceProvider.getIfAvailable()).thenReturn(apiKeyService);
        when(apiKeyService.findList(any(OauthApiKeyEntity.class))).thenReturn(List.of(apiKey));

        AgentPersonaBindResultDTO result = agentService.bindPersona("husanniang", "local");

        assertEquals("cdx_test_key", result.getApiKey());
        assertTrue(result.getEnvExample().contains("OPENCLAW_API_KEY=cdx_test_key"));
    }

    @Test
    void recommendTaskAssigneesRanksByAbilityAndAvailability() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        meta.setRequiredAbilities("[\"planning\"]");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);

        AgentRuntimeEntity planner = runtimeAgent("agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE, "[\"planning\",\"analysis\"]");
        AgentRuntimeEntity executor = runtimeAgent("agent-linchong", "林冲", AgentConstants.STATUS_ONLINE, "[\"execution\"]");
        AgentRuntimeEntity busyPlanner = runtimeAgent("agent-busy", "忙碌好汉", AgentConstants.STATUS_BUSY, "[\"planning\"]");
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null))
                .thenReturn(List.of(executor, busyPlanner, planner));

        List<AgentTaskRecommendationDTO> result = agentService.recommendTaskAssignees("task-001");

        assertEquals(2, result.size());
        assertEquals("agent-wuyong", result.getFirst().getAgent().getAgentId());
        assertEquals(100, result.getFirst().getAbilityScore());
        assertTrue(result.getFirst().getReason().contains("宋江首领建议"));
        assertEquals("agent-busy", result.get(1).getAgent().getAgentId());
    }

    @Test
    void autoAssignTaskSelectsOnlineAgentsThatCoverRequiredAbilities() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        meta.setRequiredAbilities("[\"planning\",\"execution\"]");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);

        AgentRuntimeEntity planner = ownedAgent("agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        AgentRuntimeEntity executor = ownedAgent("agent-linchong", "林冲", AgentConstants.STATUS_ONLINE, "[\"execution\"]");
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null)).thenReturn(List.of(planner, executor));
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(planner);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(executor);

        AgentTaskDTO result = agentService.autoAssignTask("task-001", new AgentTaskAssignDTO());

        assertEquals(AgentConstants.TASK_STATUS_ASSIGNED, result.getStatus());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), result.getAssignedAgentIds());
        verify(agentTaskMetaDao, never()).updateById(meta);
        verify(legacyTaskCompatibilityService).assignResolved(
                "juyiting", "jia_client", "task-001",
                List.of("agent-wuyong", "agent-linchong"), true);
    }

    @Test
    void autoAssignRechecksCompleteCoverageOnLockedRuntimeSnapshots() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        meta.setRequiredAbilities("[\"planning\",\"execution\"]");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);

        AgentRuntimeEntity recommendedPlanner = ownedAgent(
                "agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        AgentRuntimeEntity recommendedExecutor = ownedAgent(
                "agent-linchong", "林冲", AgentConstants.STATUS_ONLINE, "[\"execution\"]");
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null))
                .thenReturn(List.of(recommendedPlanner, recommendedExecutor));
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(recommendedPlanner);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(recommendedExecutor);
        AgentRuntimeEntity lockedPlanner = ownedAgent(
                "agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        AgentRuntimeEntity lockedExecutor = ownedAgent(
                "agent-linchong", "林冲", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-wuyong")).thenReturn(lockedPlanner);
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-linchong")).thenReturn(lockedExecutor);

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.autoAssignTask("task-001", new AgentTaskAssignDTO()));

        assertEquals(AgentErrorConstants.AGENT_ABILITY_MISMATCH, failure.getCode());
        assertEquals("Automatic assignment does not cover all required abilities", failure.getMessage());
        verify(legacyTaskCompatibilityService).assignResolved(
                "juyiting", "jia_client", "task-001",
                List.of("agent-wuyong", "agent-linchong"), true);
    }

    @Test
    void autoAssignTaskRejectsPartialAbilityCoverage() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        meta.setRequiredAbilities("[\"planning\",\"execution\"]");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        AgentRuntimeEntity planner = ownedAgent(
                "agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null))
                .thenReturn(List.of(planner));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> agentService.autoAssignTask("task-001", new AgentTaskAssignDTO()));

        assertEquals("No available agent can accept this task", failure.getMessage());
        verify(legacyTaskCompatibilityService, never()).assignResolved(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void archiveTaskMarksTaskArchivedWithoutUpdatingAgentRuntime() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        meta.setTaskVersion(2L);
        meta.setAssignedAgentId("agent-wuyong");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        agent.setPersonaCode("wuyong");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentTaskDTO result = agentService.archiveTask("task-001");

        assertEquals(AgentConstants.TASK_STATUS_ARCHIVED, result.getStatus());
        verify(agentTaskMetaDao).updateById(meta);
        verify(agentRuntimeDao, never()).updateById(any());
        verify(eventPublisher).publishTaskEvent(eq("task_archived"), any(AgentTaskDTO.class));
        ArgumentCaptor<AgentSceneStateDTO> stateCaptor = ArgumentCaptor.forClass(AgentSceneStateDTO.class);
        verify(sceneService).upsertState(eq("juyiting-main"), stateCaptor.capture());
        AgentSceneStateDTO state = stateCaptor.getValue();
        assertEquals("returning_home", state.getBehavior());
        assertEquals("main-seat", state.getTargetRegionId());
        assertEquals("task-001", state.getRelatedId());
        assertTrue(state.getExpiresAt() > state.getExpectedArrivalAt());
    }

    @Test
    void addTaskNoteStoresNoteAndListTaskNotesReturnsSavedNotes() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);

        AgentTaskNoteDTO request = new AgentTaskNoteDTO();
        request.setAuthorId("chcbz");
        request.setAuthorType("user");
        request.setNoteType("summary");
        request.setContent("接口联调完成，后续观察藏经阁入库。");

        AgentTaskNoteDTO created = agentService.addTaskNote("task-001", request);

        ArgumentCaptor<AgentTaskNoteEntity> noteCaptor = ArgumentCaptor.forClass(AgentTaskNoteEntity.class);
        verify(agentTaskNoteDao).insert(noteCaptor.capture());
        AgentTaskNoteEntity saved = noteCaptor.getValue();
        assertEquals("task-001", saved.getTaskId());
        assertEquals("chcbz", saved.getAuthorId());
        assertEquals("user", saved.getAuthorType());
        assertEquals("summary", saved.getNoteType());
        assertEquals("接口联调完成，后续观察藏经阁入库。", saved.getContent());
        assertNotNull(saved.getCreatedAt());
        assertEquals(saved.getCreatedAt(), created.getCreatedAt());

        AgentTaskNoteEntity oldNote = new AgentTaskNoteEntity();
        oldNote.setTaskId("task-001");
        oldNote.setAuthorId("wuyong");
        oldNote.setAuthorType("agent");
        oldNote.setNoteType("report");
        oldNote.setContent("先前纪要");
        oldNote.setCreatedAt(100L);

        AgentTaskNoteEntity newNote = new AgentTaskNoteEntity();
        newNote.setTaskId("task-001");
        newNote.setAuthorId("chcbz");
        newNote.setAuthorType("user");
        newNote.setNoteType("summary");
        newNote.setContent("最新纪要");
        newNote.setCreatedAt(200L);
        when(agentTaskNoteDao.findByTaskId("task-001")).thenReturn(List.of(oldNote, newNote));

        List<AgentTaskNoteDTO> notes = agentService.listTaskNotes("task-001");

        assertEquals(2, notes.size());
        assertEquals("先前纪要", notes.get(0).getContent());
        assertEquals("最新纪要", notes.get(1).getContent());
        verify(agentTaskNoteDao).findByTaskId("task-001");
    }

    @Test
    void countTasksByStatusIgnoresSelectedStatusAndKeepsAbilityAndKeywordFilters() {
        AgentTaskMetaEntity assigned = new AgentTaskMetaEntity();
        assigned.setTaskId("reward-001");
        assigned.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);

        AgentTaskMetaEntity running = new AgentTaskMetaEntity();
        running.setTaskId("reward-002");
        running.setRewardStatus(AgentConstants.TASK_STATUS_RUNNING);

        AgentTaskMetaEntity otherKeyword = new AgentTaskMetaEntity();
        otherKeyword.setTaskId("daily-003");
        otherKeyword.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);

        when(agentTaskMetaDao.search(null, "planning")).thenReturn(List.of(assigned, running, otherKeyword));

        AgentTaskSearchDTO request = new AgentTaskSearchDTO();
        request.setStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        request.setAbility("planning");
        request.setKeyword("reward");

        Map<String, Long> counts = agentService.countTasksByStatus(request);

        assertEquals(2L, counts.get("total"));
        assertEquals(1L, counts.get(AgentConstants.TASK_STATUS_ASSIGNED));
        assertEquals(1L, counts.get(AgentConstants.TASK_STATUS_RUNNING));
        assertEquals(0L, counts.get(AgentConstants.TASK_STATUS_COMPLETED));
        verify(agentTaskMetaDao).search(null, "planning");
    }

    @Test
    void searchTasksMatchesKeywordAgainstEnrichedTitleAndDescription() {
        when(taskServiceProvider.getIfAvailable()).thenReturn(taskService);

        AgentTaskMetaEntity first = new AgentTaskMetaEntity();
        first.setTaskId("1");
        first.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        first.setRequiredAbilities("[\"planning\"]");

        AgentTaskMetaEntity second = new AgentTaskMetaEntity();
        second.setTaskId("2");
        second.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        second.setRequiredAbilities("[\"review\"]");

        TaskPlanEntity firstPlan = new TaskPlanEntity();
        firstPlan.setName("夜探祝家庄");
        firstPlan.setDescription("先探路，再回厅前公议");
        TaskPlanEntity secondPlan = new TaskPlanEntity();
        secondPlan.setName("整理案卷");
        secondPlan.setDescription("普通归档");

        when(agentTaskMetaDao.search(null, null)).thenReturn(List.of(first, second));
        when(taskService.get(1L)).thenReturn(firstPlan);
        when(taskService.get(2L)).thenReturn(secondPlan);

        AgentTaskSearchDTO request = new AgentTaskSearchDTO();
        request.setKeyword("祝家庄");

        PageInfo<AgentTaskDTO> result = agentService.searchTasks(request);

        assertEquals(1, result.getList().size());
        assertEquals("1", result.getList().getFirst().getId());
        assertEquals("夜探祝家庄", result.getList().getFirst().getTitle());
        assertEquals(1L, result.getTotal());
    }

    @Test
    void searchTasksPaginatesAfterKeywordFiltering() {
        when(taskServiceProvider.getIfAvailable()).thenReturn(taskService);

        AgentTaskMetaEntity first = new AgentTaskMetaEntity();
        first.setTaskId("1");
        first.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);

        AgentTaskMetaEntity second = new AgentTaskMetaEntity();
        second.setTaskId("2");
        second.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);

        TaskPlanEntity firstPlan = new TaskPlanEntity();
        firstPlan.setName("无关榜文");
        TaskPlanEntity secondPlan = new TaskPlanEntity();
        secondPlan.setName("巡山榜文");

        when(agentTaskMetaDao.search(null, null)).thenReturn(List.of(first, second));
        when(taskService.get(1L)).thenReturn(firstPlan);
        when(taskService.get(2L)).thenReturn(secondPlan);

        AgentTaskSearchDTO request = new AgentTaskSearchDTO();
        request.setKeyword("巡山");
        request.setPageNum(1);
        request.setPageSize(1);

        PageInfo<AgentTaskDTO> result = agentService.searchTasks(request);

        assertEquals(1, result.getList().size());
        assertEquals("2", result.getList().getFirst().getId());
        assertEquals(1L, result.getTotal());
    }

    @Test
    void assignTaskRejectsOfflineAgent() {
        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setAgentId("agent-001");
        agent.setStatus(AgentConstants.STATUS_OFFLINE);
        markOwned(agent);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(agent);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);

        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-001");

        AgentServiceImpl.AgentBizException thrown = assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> agentService.assignTask("task-001", request));

        assertEquals(AgentErrorConstants.AGENT_OFFLINE, thrown.getCode());
        verify(agentTaskMetaDao, never()).insert(any());
        verify(agentTaskMetaDao, never()).updateById(any());
    }

    @Test
    void listRosterFiltersByStatusWithoutPublishingRuntimeStatus() {
        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setAgentId("agent-001");
        agent.setName("Wu Yong");
        agent.setStatus(AgentConstants.STATUS_OFFLINE);
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", AgentConstants.STATUS_OFFLINE, "planning"))
                .thenReturn(List.of(agent));

        List<AgentRuntimeDTO> result = agentService.listRoster(AgentConstants.STATUS_OFFLINE, "planning", 1, 20)
                .getList();

        assertEquals(1, result.size());
        assertEquals("agent-001", result.getFirst().getAgentId());
        assertEquals(AgentConstants.STATUS_OFFLINE, result.getFirst().getStatus());
        verify(agentRuntimeDao).findRosterByOwner("jia_client", "juyiting", AgentConstants.STATUS_OFFLINE, "planning");
        verify(eventPublisherProvider, never()).getIfAvailable();
    }

    @Test
    void reportRejectsMissingAgentIdBeforeCompatibilityWrite() {
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setStatus(AgentConstants.TASK_STATUS_RUNNING);

        assertThrows(IllegalArgumentException.class,
                () -> agentService.reportTask("task-001", request));
        verify(legacyTaskCompatibilityService, never()).reportResolved(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void reportRejectsBodyAgentOutsideCurrentOwnerScope() {
        AgentRuntimeEntity foreign = new AgentRuntimeEntity();
        foreign.setAgentId("agt_ffffffffffffffffffffffffffffffff");
        foreign.setClientId("other-client");
        foreign.setOwnerJiacn("other-owner");
        when(agentRuntimeDao.findByAgentId(foreign.getAgentId())).thenReturn(foreign);
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId(foreign.getAgentId());
        request.setStatus(AgentConstants.TASK_STATUS_RUNNING);

        AgentServiceImpl.AgentBizException error = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.reportTask("task-001", request));
        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, error.getCode());
        verify(legacyTaskCompatibilityService, never()).reportResolved(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void reportRunningUpdatesAgentBusyAndPublishesTaskEvent() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_RUNNING);
        meta.setTaskVersion(1L);
        meta.setAssignedAgentId("agent-001");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);
        when(legacyTaskCompatibilityService.reportResolved(
                "juyiting", "jia_client", "task-001", "agent-001",
                AgentConstants.TASK_STATUS_RUNNING, null))
                .thenReturn(new AgentLegacyTaskCompatibilityService.ReportOutcome(
                        AgentConstants.TASK_STATUS_RUNNING, 1L, true, false,
                        "agent-001", List.of("agent-001")));

        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setId(2L);
        agent.setAgentId("agent-001");
        agent.setName("Wu Yong");
        agent.setStatus(AgentConstants.STATUS_ONLINE);
        markOwned(agent);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(agent);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-001");
        request.setStatus(AgentConstants.TASK_STATUS_RUNNING);
        request.setCurrentTaskTitle("Analyze order issue");

        AgentTaskDTO result = agentService.reportTask("task-001", request);

        assertEquals("task-001", result.getId());
        assertEquals(AgentConstants.TASK_STATUS_RUNNING, result.getStatus());
        assertEquals("agent-001", result.getAssignedAgentId());
        assertEquals("Wu Yong", result.getAssignedAgentName());

        verify(legacyTaskCompatibilityService).reportResolved(
                "juyiting", "jia_client", "task-001", "agent-001",
                AgentConstants.TASK_STATUS_RUNNING, null);

        ArgumentCaptor<AgentRuntimeEntity> agentCaptor = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao).updateById(agentCaptor.capture());
        assertEquals(AgentConstants.STATUS_BUSY, agentCaptor.getValue().getStatus());
        assertEquals("task-001", agentCaptor.getValue().getCurrentTaskId());
        assertEquals("Analyze order issue", agentCaptor.getValue().getCurrentTaskTitle());

        verify(eventPublisher).publishAgentStatus(any(), any(), any(AgentRuntimeDTO.class));
        verify(eventPublisher).publishTaskEvent(eq("task_running"), any(AgentTaskDTO.class));
    }

    @Test
    void secondaryMemberCanListTaskWhenCompatibilityColumnStoresOnlyPrimary() {
        AgentRuntimeEntity secondary = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_ONLINE, "[\"execution\"]");
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(secondary);
        AgentTaskMemberEntity membership = new AgentTaskMemberEntity();
        membership.setTenantId("juyiting");
        membership.setClientId("jia_client");
        membership.setTaskId("task-001");
        membership.setAgentId("agent-linchong");
        membership.setMemberStatus("working");
        when(agentTaskMemberDao.listByAgent(
                "juyiting", "jia_client", "agent-linchong", null, 500))
                .thenReturn(List.of(membership));
        AgentTaskMemberEntity primary = taskMember(
                "juyiting", "jia_client", "task-001", "agent-wuyong", "working");
        when(agentTaskMemberDao.listByTask("juyiting", "jia_client", "task-001"))
                .thenReturn(List.of(primary, membership));
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_RUNNING);
        meta.setAssignedAgentId("agent-wuyong");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);

        List<AgentTaskDTO> result = agentService.getAgentTasks("agent-linchong");

        assertEquals(1, result.size());
        assertEquals(List.of("agent-wuyong", "agent-linchong"),
                result.getFirst().getAssignedAgentIds());
        verify(agentTaskMetaDao, never()).findByAgentId("juyiting", "jia_client", "agent-linchong", 500);
    }


    @Test
    void legacyTaskFallbackPushesExactScopeAndAgentIntoBoundedDaoQuery() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_ONLINE, "[]");
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(agent);
        AgentTaskMetaEntity task = new AgentTaskMetaEntity();
        task.setId(1L);
        task.setTaskId("task-legacy");
        task.setTenantId("juyiting");
        task.setClientId("jia_client");
        task.setAssignedAgentId("agent-linchong");
        task.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        when(agentTaskMetaDao.findByAgentId(
                "juyiting", "jia_client", "agent-linchong", 500))
                .thenReturn(List.of(task));

        List<AgentTaskDTO> result = agentService.getAgentTasks("agent-linchong");

        assertEquals(1, result.size());
        assertEquals("task-legacy", result.getFirst().getId());
        verify(agentTaskMetaDao).findByAgentId(
                "juyiting", "jia_client", "agent-linchong", 500);
    }

    @Test
    void legacyTaskFallbackTruncationFailsClosed() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_ONLINE, "[]");
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(agent);
        AgentTaskMetaEntity task = new AgentTaskMetaEntity();
        task.setTaskId("task-legacy");
        task.setTenantId("juyiting");
        task.setClientId("jia_client");
        task.setAssignedAgentId("agent-linchong");
        when(agentTaskMetaDao.findByAgentId(
                "juyiting", "jia_client", "agent-linchong", 500))
                .thenReturn(java.util.Collections.nCopies(500, task));

        assertThrows(IllegalArgumentException.class,
                () -> agentService.getAgentTasks("agent-linchong"));
    }

    @Test
    void legacyTaskFallbackRejectsScopeAgentAndTaskIdDrift() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_ONLINE, "[]");
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(agent);
        AgentTaskMetaEntity drifted = new AgentTaskMetaEntity();
        drifted.setTaskId("task-legacy ");
        drifted.setTenantId("JUYITING");
        drifted.setClientId("jia_client");
        drifted.setAssignedAgentId("agent-wuyong");
        when(agentTaskMetaDao.findByAgentId(
                "juyiting", "jia_client", "agent-linchong", 500))
                .thenReturn(List.of(drifted));

        assertThrows(IllegalArgumentException.class,
                () -> agentService.getAgentTasks("agent-linchong"));
    }

    @Test
    void memberTaskProjectionFailsClosedWhenScopedTaskIsMissing() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_ONLINE, "[]");
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(agent);
        AgentTaskMemberEntity membership = taskMember(
                "juyiting", "jia_client", "task-missing", "agent-linchong", "working");
        when(agentTaskMemberDao.listByAgent(
                "juyiting", "jia_client", "agent-linchong", null, 500))
                .thenReturn(List.of(membership));

        assertThrows(IllegalArgumentException.class,
                () -> agentService.getAgentTasks("agent-linchong"));
        verify(agentTaskMetaDao, never()).findByAgentId("juyiting", "jia_client", "agent-linchong", 500);
    }

    @Test
    void agentTaskMembershipSnapshotTruncationFailsClosed() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_ONLINE, "[]");
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(agent);
        List<AgentTaskMemberEntity> memberships = java.util.stream.IntStream.range(0, 500)
                .mapToObj(index -> taskMember(
                        "juyiting", "jia_client", "task-" + index,
                        "agent-linchong", "working"))
                .toList();
        when(agentTaskMemberDao.listByAgent(
                "juyiting", "jia_client", "agent-linchong", null, 500))
                .thenReturn(memberships);

        assertThrows(IllegalArgumentException.class,
                () -> agentService.getAgentTasks("agent-linchong"));
        verify(agentTaskMetaDao, never()).findByTaskId(any(), any(), any());
    }

    @Test
    void scopedTaskProjectionRejectsCaseDriftFromPersistence() {
        AgentTaskMetaEntity drifted = new AgentTaskMetaEntity();
        drifted.setTaskId("task-001");
        drifted.setTenantId("JUYITING");
        drifted.setClientId("jia_client");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(drifted);

        assertThrows(IllegalArgumentException.class, () -> agentService.getTask("task-001"));
    }

    @Test
    void assignmentAbilityValidationIsCaseInsensitive() {
        AgentRuntimeEntity locked = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(locked);
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-wuyong")).thenReturn(locked);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        meta.setRequiredAbilities("[\"PLANNING\"]");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        AgentTaskDTO assigned = agentService.assignTask("task-001", request);

        assertEquals("agent-wuyong", assigned.getAssignedAgentId());
        verify(legacyTaskCompatibilityService).assignResolved(
                "juyiting", "jia_client", "task-001", List.of("agent-wuyong"), false);
    }

    @Test
    void assignmentValidatesRuntimeSnapshotLockedAfterCompatibilityWrite() {
        AgentRuntimeEntity observed = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        AgentRuntimeEntity locked = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_OFFLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(observed);
        when(agentRuntimeDao.findByAgentIdForUpdate("agent-wuyong")).thenReturn(locked);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        meta.setRequiredAbilities("[\"planning\"]");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> agentService.assignTask("task-001", request));

        verify(legacyTaskCompatibilityService).assignResolved(
                "juyiting", "jia_client", "task-001", List.of("agent-wuyong"), false);
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
    }

    @Test
    void duplicateAssignmentReturnsCurrentProjectionWithoutDispatchOrEvents() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_OFFLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        meta.setAssignedAgentId("agent-wuyong");
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        when(legacyTaskCompatibilityService.assignResolved(
                "juyiting", "jia_client", "task-001", List.of("agent-wuyong"), false))
                .thenReturn(new AgentLegacyTaskCompatibilityService.AssignOutcome(
                        List.of("agent-wuyong"), false));
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        assertEquals(List.of("agent-wuyong"), result.getAssignedAgentIds());
        assertTrue(result.getActionDispatchResults().isEmpty());
        verify(eventPublisher, never()).publishAgentAction(any());
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
        verify(sceneService, never()).upsertState(any(), any());
        verify(agentRuntimeDao, never()).updateById(any());
    }

    @Test
    void reportProjectionVersionDriftFailsBeforeRuntimeOrEvents() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_RUNNING);
        meta.setTaskVersion(2L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        when(legacyTaskCompatibilityService.reportResolved(
                "juyiting", "jia_client", "task-001", "agent-wuyong",
                AgentConstants.TASK_STATUS_RUNNING, null))
                .thenReturn(new AgentLegacyTaskCompatibilityService.ReportOutcome(
                        AgentConstants.TASK_STATUS_RUNNING, 1L, true, false,
                        "agent-wuyong", List.of("agent-wuyong")));
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-wuyong");
        request.setStatus(AgentConstants.TASK_STATUS_RUNNING);

        assertThrows(IllegalArgumentException.class,
                () -> agentService.reportTask("task-001", request));

        verify(agentRuntimeDao, never()).updateById(any());
        verify(eventPublisher, never()).publishAgentStatus(any(), any(), any());
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
        verify(sceneService, never()).upsertState(any(), any());
    }

    @Test
    void duplicateReportHasNoRuntimeEventOrSceneSideEffects() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        meta.setTaskVersion(2L);
        meta.setAssignedAgentId("agent-wuyong");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        when(legacyTaskCompatibilityService.reportResolved(
                "juyiting", "jia_client", "task-001", "agent-wuyong",
                AgentConstants.TASK_STATUS_COMPLETED, null))
                .thenReturn(new AgentLegacyTaskCompatibilityService.ReportOutcome(
                        AgentConstants.TASK_STATUS_COMPLETED, 2L, false, false,
                        "agent-wuyong", List.of("agent-wuyong")));
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-wuyong");
        request.setStatus(AgentConstants.TASK_STATUS_COMPLETED);

        AgentTaskDTO result = agentService.reportTask("task-001", request);

        assertEquals(AgentConstants.TASK_STATUS_COMPLETED, result.getStatus());
        verify(agentRuntimeDao, never()).updateById(any());
        verify(eventPublisher, never()).publishAgentStatus(any(), any(), any());
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
        verify(sceneService, never()).upsertState(any(), any());
    }

    @Test
    void firstTerminalAggregateClearsAndReturnsHomeForWholeTeam() {
        AgentRuntimeEntity first = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_BUSY, "[\"planning\"]");
        first.setCurrentTaskId("task-001");
        first.setCurrentTaskTitle("Coordinate");
        AgentRuntimeEntity second = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_BUSY, "[\"execution\"]");
        second.setCurrentTaskId("task-001");
        second.setCurrentTaskTitle("Execute");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(first);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(second);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(sceneServiceProvider.getIfAvailable()).thenReturn(sceneService);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        meta.setTaskVersion(2L);
        meta.setAssignedAgentId("agent-wuyong");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        when(legacyTaskCompatibilityService.reportResolved(
                "juyiting", "jia_client", "task-001", "agent-linchong",
                AgentConstants.TASK_STATUS_COMPLETED, null))
                .thenReturn(new AgentLegacyTaskCompatibilityService.ReportOutcome(
                        AgentConstants.TASK_STATUS_COMPLETED, 2L, true, true,
                        "agent-linchong", List.of("agent-wuyong", "agent-linchong")));
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-linchong");
        request.setStatus(AgentConstants.TASK_STATUS_COMPLETED);

        AgentTaskDTO result = agentService.reportTask("task-001", request);

        assertEquals(List.of("agent-wuyong", "agent-linchong"), result.getAssignedAgentIds());
        ArgumentCaptor<AgentRuntimeEntity> runtimeCaptor = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao, times(2)).updateById(runtimeCaptor.capture());
        assertTrue(runtimeCaptor.getAllValues().stream()
                .allMatch(runtime -> AgentConstants.STATUS_ONLINE.equals(runtime.getStatus())
                        && runtime.getCurrentTaskId() == null
                        && runtime.getCurrentTaskTitle() == null));
        verify(eventPublisher, times(2)).publishAgentStatus(any(), any(), any());
        ArgumentCaptor<AgentSceneStateDTO> sceneCaptor = ArgumentCaptor.forClass(AgentSceneStateDTO.class);
        verify(sceneService, times(2)).upsertState(eq("juyiting-main"), sceneCaptor.capture());
        assertEquals(Set.of("agent-wuyong", "agent-linchong"),
                sceneCaptor.getAllValues().stream()
                        .map(AgentSceneStateDTO::getAgentId).collect(java.util.stream.Collectors.toSet()));
    }


    @Test
    void assignmentDispatchAndEventsRunOnlyAfterCommit() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AgentTaskDTO result = agentService.assignTask("task-001", request);

            assertTrue(result.getActionDispatchResults().isEmpty());
            verify(eventPublisher, never()).publishAgentAction(any());
            verify(eventPublisher, never()).publishTaskEvent(any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(eventPublisher).publishAgentAction(any());
            verify(eventPublisher).publishTaskEvent(eq("task_assigned"), any());
            assertEquals(1, result.getActionDispatchResults().size());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void reportEventsRunOnlyAfterCommitWhileRuntimeUpdateStaysTransactional() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_RUNNING);
        meta.setTaskVersion(1L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        when(legacyTaskCompatibilityService.reportResolved(
                "juyiting", "jia_client", "task-001", "agent-wuyong",
                AgentConstants.TASK_STATUS_RUNNING, null))
                .thenReturn(new AgentLegacyTaskCompatibilityService.ReportOutcome(
                        AgentConstants.TASK_STATUS_RUNNING, 1L, true, false,
                        "agent-wuyong", List.of("agent-wuyong")));
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-wuyong");
        request.setStatus(AgentConstants.TASK_STATUS_RUNNING);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            agentService.reportTask("task-001", request);

            verify(agentRuntimeDao).updateById(any());
            verify(agentRuntimeDao, never()).findRosterByOwner(
                    "jia_client", "juyiting", null, null);
            verify(eventPublisher, never()).publishAgentStatus(any(), any(), any());
            verify(eventPublisher, never()).publishTaskEvent(any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(agentRuntimeDao).findRosterByOwner(
                    "jia_client", "juyiting", null, null);
            verify(eventPublisher).publishAgentStatus(any(), any(), any());
            verify(eventPublisher).publishTaskEvent(eq("task_running"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void failedTeamTerminalMarksOnlyReporterErroredAndReleasesEveryone() {
        AgentRuntimeEntity first = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_BUSY, "[\"planning\"]");
        first.setCurrentTaskId("task-001");
        AgentRuntimeEntity second = ownedAgent(
                "agent-linchong", "Lin Chong", AgentConstants.STATUS_BUSY, "[\"execution\"]");
        second.setCurrentTaskId("task-001");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(first);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(second);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_FAILED);
        meta.setTaskVersion(2L);
        meta.setFailureReason("required work failed");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        when(legacyTaskCompatibilityService.reportResolved(
                "juyiting", "jia_client", "task-001", "agent-linchong",
                AgentConstants.TASK_STATUS_FAILED, "required work failed"))
                .thenReturn(new AgentLegacyTaskCompatibilityService.ReportOutcome(
                        AgentConstants.TASK_STATUS_FAILED, 2L, true, true,
                        "agent-linchong", List.of("agent-wuyong", "agent-linchong")));
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-linchong");
        request.setStatus(AgentConstants.TASK_STATUS_FAILED);
        request.setFailureReason("required work failed");

        agentService.reportTask("task-001", request);

        ArgumentCaptor<AgentRuntimeEntity> runtimeCaptor = ArgumentCaptor.forClass(
                AgentRuntimeEntity.class);
        verify(agentRuntimeDao, times(2)).updateById(runtimeCaptor.capture());
        Map<String, AgentRuntimeEntity> byId = runtimeCaptor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AgentRuntimeEntity::getAgentId, runtime -> runtime));
        assertEquals(AgentConstants.STATUS_ONLINE, byId.get("agent-wuyong").getStatus());
        assertEquals(AgentConstants.STATUS_ERROR, byId.get("agent-linchong").getStatus());
        assertTrue(byId.values().stream().allMatch(runtime -> runtime.getCurrentTaskId() == null));
    }

    @Test
    void getStatsBuildsTaskCountersAndAverageDuration() {
        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setAgentId("agent-001");
        agent.setName("Wu Yong");
        agent.setStatus(AgentConstants.STATUS_ONLINE);
        markOwned(agent);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(agent);

        AgentTaskMetaEntity completed = new AgentTaskMetaEntity();
        completed.setTaskId("task-001");
        completed.setTenantId("juyiting");
        completed.setClientId("jia_client");
        completed.setAssignedAgentId("agent-001");
        completed.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        completed.setStartedAt(1_000L);
        completed.setCompletedAt(61_000L);

        AgentTaskMetaEntity failed = new AgentTaskMetaEntity();
        failed.setTaskId("task-002");
        failed.setTenantId("juyiting");
        failed.setClientId("jia_client");
        failed.setAssignedAgentId("agent-001");
        failed.setRewardStatus(AgentConstants.TASK_STATUS_FAILED);
        when(agentTaskMetaDao.findByAgentId("juyiting", "jia_client", "agent-001", 500))
                .thenReturn(List.of(completed, failed));

        AgentRuntimeDTO result = agentService.getStats("agent-001");

        AgentStatsDTO stats = result.getStats();
        assertEquals(1, stats.getCompletedTaskCount());
        assertEquals(1, stats.getFailedTaskCount());
        assertEquals(60L, stats.getAverageDurationSeconds());
    }

    private AgentTaskMemberEntity taskMember(
            String tenantId, String clientId, String taskId, String agentId, String status) {
        AgentTaskMemberEntity member = new AgentTaskMemberEntity();
        member.setTenantId(tenantId);
        member.setClientId(clientId);
        member.setTaskId(taskId);
        member.setAgentId(agentId);
        member.setMemberStatus(status);
        return member;
    }

    private void markOwned(AgentRuntimeEntity agent) {
        agent.setClientId("jia_client");
        agent.setOwnerJiacn("juyiting");
        agent.setBindingId(1L);
        AgentPersonaBindingEntity binding = binding(
                agent.getAgentId(), agent.getPersonaCode() == null ? agent.getAgentId() : agent.getPersonaCode());
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_ACTIVE);
        org.mockito.Mockito.lenient().when(agentIdentityService.requireCanonicalAgentIdInScope(
                "juyiting", "jia_client", "juyiting", agent.getAgentId())).thenReturn(agent.getAgentId());
        org.mockito.Mockito.lenient().when(agentIdentityService.requireActiveIdentityForBinding(
                "juyiting", "jia_client", "juyiting", 1L, agent.getAgentId())).thenReturn(identity);
        org.mockito.Mockito.lenient().when(agentIdentityService.requireActiveBinding(identity, null)).thenReturn(binding);
    }

    private AgentRuntimeEntity ownedAgent(String agentId, String name, String status, String abilities) {
        AgentRuntimeEntity agent = runtimeAgent(agentId, name, status, abilities);
        markOwned(agent);
        return agent;
    }

    private AgentRuntimeEntity runtimeAgent(String agentId, String name, String status, String abilities) {
        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setAgentId(agentId);
        agent.setName(name);
        agent.setPersonaCode(agentId);
        agent.setStatus(status);
        agent.setAbilities(abilities);
        return agent;
    }

    private AgentPersonaBindingEntity binding(String agentId, String personaCode) {
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(1L);
        binding.setClientId("jia_client");
        binding.setTenantId("juyiting");
        binding.setJiacn("juyiting");
        binding.setAgentId(agentId);
        binding.setPersonaCode(personaCode);
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        return binding;
    }

    private AgentIdentityRegistryEntity identity(AgentPersonaBindingEntity binding, String lifecycle) {
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setId(10L);
        identity.setCanonicalAgentId(binding.getAgentId());
        identity.setCanonicalType(binding.getAgentId().matches("agt_[0-9a-f]{32}")
                ? AgentConstants.IDENTITY_TYPE_OPAQUE
                : AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        identity.setLifecycleStatus(lifecycle);
        identity.setTenantId(binding.getJiacn());
        identity.setClientId(binding.getClientId());
        identity.setOwnerJiacn(binding.getJiacn());
        identity.setBindingId(binding.getId());
        identity.setAuditReason("test");
        return identity;
    }

    private AgentPersonaEntity persona(String code, String name, String title) {
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setPersonaCode(code);
        persona.setName(name);
        persona.setTitle(title);
        persona.setAbilities("[\"planning\",\"research\"]");
        persona.setActive(true);
        persona.setSystemAgent(false);
        return persona;
    }
}
