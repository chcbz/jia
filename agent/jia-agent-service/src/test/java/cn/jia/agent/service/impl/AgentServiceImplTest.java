package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
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
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.mapper.AgentPersonaCatalogBindingRow;
import cn.jia.agent.mapper.AgentTaskSearchRow;
import cn.jia.agent.mapper.AgentTaskStatsRow;
import cn.jia.agent.mapper.AgentTaskStatusCountRow;
import cn.jia.agent.mapper.AgentTaskStatsScope;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
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
    @Mock
    AgentTaskMutationTransaction mutationTransaction;
    @Mock
    AgentTaskEventWriter taskEventWriter;
    @Mock
    AgentCommandTransportCapture commandTransportCapture;
    private final AtomicReference<AgentTaskMetaEntity> lastInsertedTask = new AtomicReference<>();
    AgentServiceImpl agentService;

    @BeforeEach
    void setUpAgentService() {
        SecurityContextHolder.clearContext();
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
                new AgentSceneFeatureFlags(true, true), mutationTransaction, taskEventWriter);
        org.mockito.Mockito.lenient().when(legacyTaskCompatibilityService.resolveAgentIds(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> List.copyOf(invocation.<List<String>>getArgument(3)));
        org.mockito.Mockito.lenient().when(legacyTaskCompatibilityService.resolveAgentId(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        org.mockito.Mockito.lenient().when(legacyTaskCompatibilityService.assignResolved(
                        any(), any(), any(), any(), anyBoolean(),
                        any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class)))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(3);
                    AgentTaskMetaEntity lockedTask = agentTaskMetaDao.findByTaskId(
                            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                    AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator validator =
                            invocation.getArgument(5);
                    validator.validate(lockedTask, ids);
                    return new AgentLegacyTaskCompatibilityService.AssignOutcome(
                            ids, true, "evt-task-assigned", 1_000L);
                });
        org.mockito.Mockito.lenient().when(agentRuntimeDao.updateById(any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(agentIdentityService.lockActiveCanonicalAgentIdsInScope(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> List.copyOf(invocation.<List<String>>getArgument(3)));
        org.mockito.Mockito.lenient().when(agentRuntimeDao.findByAgentIdForUpdate(any()))
                .thenAnswer(invocation -> agentRuntimeDao.findByAgentId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient().when(agentTaskMetaDao.insert(any(AgentTaskMetaEntity.class)))
                .thenAnswer(invocation -> {
                    AgentTaskMetaEntity meta = invocation.getArgument(0);
                    if (meta.getId() == null) meta.setId(1L);
                    if (meta.getCreateTime() == null) meta.setCreateTime(1_000L);
                    if (meta.getUpdateTime() == null) meta.setUpdateTime(1_000L);
                    lastInsertedTask.set(meta);
                    return 1;
                });
        org.mockito.Mockito.lenient().when(agentTaskMetaDao.updateById(any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(agentTaskMetaDao.updateStatusByVersion(
                        any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(1);
        org.mockito.Mockito.lenient().when(agentTaskMetaDao.rekeyReservedTaskRoot(
                        any(), any(), any(), any(), anyLong()))
                .thenReturn(1);
        org.mockito.Mockito.lenient().when(agentTaskMetaDao.deleteReservedTaskRoot(
                        any(), any(), any()))
                .thenReturn(1);
        org.mockito.Mockito.lenient().when(agentTaskNoteDao.insert(any(AgentTaskNoteEntity.class)))
                .thenAnswer(invocation -> {
                    AgentTaskNoteEntity note = invocation.getArgument(0);
                    if (note.getId() == null) note.setId(1L);
                    return 1;
                });
        org.mockito.Mockito.lenient().when(mutationTransaction.executeAfterTaskRootReservation(
                        any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.TaskRootReservation reservation = invocation.getArgument(3);
                    boolean created = reservation.reserve() == 1;
                    AgentTaskMetaEntity root = lastInsertedTask.get();
                    if (root == null) {
                        root = agentTaskMetaDao.findByTaskId(
                                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                    }
                    AgentTaskMutationTransaction.ReservedTaskMutation<?> mutation = invocation.getArgument(4);
                    return mutation.apply(root, created);
                });
        org.mockito.Mockito.lenient().when(mutationTransaction.executeWithLockedTaskRoot(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMetaEntity root = agentTaskMetaDao.findByTaskId(
                            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
                    return mutation.apply(root);
                });
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
        AtomicReference<AgentRuntimeEntity> committedRuntime = new AtomicReference<>();
        when(agentRuntimeDao.findByAgentId("agent-001"))
                .thenAnswer(invocation -> committedRuntime.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentRuntimeEntity inserted = invocation.getArgument(0);
            committedRuntime.set(inserted);
            return 1;
        }).when(agentRuntimeDao).insert(any(AgentRuntimeEntity.class));
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
        org.mockito.Mockito.doReturn(lockedCurrent).when(agentRuntimeDao)
                .findByAgentIdForUpdate("agent-001");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(lockedCurrent);

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId("agent-001");
        agentService.register(request);

        assertEquals("[\"current-skill\"]", lockedCurrent.getAbilities());
        assertEquals("[\"stale-skill\"]", stale.getAbilities());
        verify(agentRuntimeDao).findByAgentId("agent-001");
        verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
        verify(agentRuntimeDao).updateById(lockedCurrent);
        org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
                agentIdentityService, agentRuntimeDao);
        lockOrder.verify(agentIdentityService).activateForFirstRegistration(identity);
        lockOrder.verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
        lockOrder.verify(agentRuntimeDao).updateById(lockedCurrent);
        lockOrder.verify(agentRuntimeDao).findByAgentId("agent-001");
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
    void delayedPresenceCallbackPublishesCurrentOfflineInsideScopeInsteadOfCapturedOnline() {
        AgentRuntimeEntity updating = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_OFFLINE, "[\"planning\"]");
        AgentRuntimeEntity committedOffline = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_OFFLINE, "[\"current-offline\"]");
        committedOffline.setEndpoint("offline-after-unbind");
        AtomicReference<AgentRuntimeEntity> committedRuntime = new AtomicReference<>(updating);
        AtomicBoolean insideScope = new AtomicBoolean(false);
        AgentScopePublicationCoordinator coordinated = coordinatedPublication(insideScope);
        agentService = newAgentService(coordinated, new AgentSceneFeatureFlags(true, true));
        org.mockito.Mockito.doReturn(updating).when(agentRuntimeDao)
                .findByAgentIdForUpdate("agent-001");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenAnswer(invocation -> {
            assertTrue(insideScope.get(), "committed reread must run inside the exact-scope lock");
            return committedRuntime.get();
        });
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null))
                .thenReturn(List.of(committedOffline));
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        AgentStatusDTO status = new AgentStatusDTO();
        status.setStatus(AgentConstants.STATUS_ONLINE);
        status.setAbilities(List.of("captured-online"));

        List<TransactionSynchronization> delayedCallbacks;
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AgentRuntimeDTO result = agentService.updateStatus("agent-001", status);
            assertEquals(AgentConstants.STATUS_ONLINE, result.getStatus());
            delayedCallbacks = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            verify(agentRuntimeDao, never()).findByAgentId("agent-001");
            verify(coordinated, never()).execute(any(), any(), any(Runnable.class));
            verify(eventPublisher, never()).publishAgentStatus(any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        committedRuntime.set(committedOffline);
        AgentRuntimeDTO alreadyPublishedOffline = new AgentRuntimeDTO();
        alreadyPublishedOffline.setAgentId("agent-001");
        alreadyPublishedOffline.setStatus(AgentConstants.STATUS_OFFLINE);
        alreadyPublishedOffline.setEndpoint("offline-after-unbind");
        coordinated.execute("jia_client", "juyiting", () -> eventPublisher.publishAgentStatus(
                "jia_client", "juyiting", alreadyPublishedOffline));
        delayedCallbacks.forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<AgentRuntimeDTO> publications = ArgumentCaptor.forClass(AgentRuntimeDTO.class);
        verify(eventPublisher, times(2)).publishAgentStatus(
                eq("jia_client"), eq("juyiting"), publications.capture());
        assertEquals(List.of(AgentConstants.STATUS_OFFLINE, AgentConstants.STATUS_OFFLINE),
                publications.getAllValues().stream().map(AgentRuntimeDTO::getStatus).toList());
        assertEquals("offline-after-unbind", publications.getAllValues().get(1).getEndpoint());
        assertFalse(publications.getAllValues().stream()
                .anyMatch(runtime -> AgentConstants.STATUS_ONLINE.equals(runtime.getStatus())));
        verify(agentRuntimeDao).findByAgentId("agent-001");
        verify(eventPublisher).publishCapabilityIndex(eq("jia_client"), eq("juyiting"), any());
    }

    @Test
    void delayedRegisterCallbackRereadsInsideScopeAndSkipsMismatchedBinding() {
        AgentPersonaBindingEntity binding = binding("agent-001", "wuyong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_PROVISIONED);
        AgentRuntimeEntity replacedBinding = ownedAgent(
                "agent-001", "吴用", AgentConstants.STATUS_OFFLINE, "[\"replacement\"]");
        replacedBinding.setBindingId(2L);
        AtomicBoolean insideScope = new AtomicBoolean(false);
        AgentScopePublicationCoordinator coordinated = coordinatedPublication(insideScope);
        agentService = newAgentService(coordinated, new AgentSceneFeatureFlags(true, true));
        when(agentIdentityService.requireRegistrationIdentityInScope(
                "juyiting", "jia_client", "juyiting", "agent-001")).thenReturn(identity);
        when(agentIdentityService.requireActiveBinding(identity, null)).thenReturn(binding);
        when(agentIdentityService.activateForFirstRegistration(identity)).thenReturn(identity);
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona("wuyong", "吴用", "智多星"));
        org.mockito.Mockito.doReturn(null).when(agentRuntimeDao)
                .findByAgentIdForUpdate("agent-001");
        when(agentRuntimeDao.findByAgentId("agent-001")).thenAnswer(invocation -> {
            assertTrue(insideScope.get(), "committed reread must run inside the exact-scope lock");
            return replacedBinding;
        });
        org.mockito.Mockito.lenient().when(eventPublisherProvider.getIfAvailable())
                .thenReturn(eventPublisher);
        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId("agent-001");

        List<TransactionSynchronization> delayedCallbacks;
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AgentRegisterResultDTO result = agentService.register(request);
            assertEquals(AgentConstants.STATUS_ONLINE, result.getStatus());
            delayedCallbacks = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            verify(agentRuntimeDao, never()).findByAgentId("agent-001");
            verify(coordinated, never()).execute(any(), any(), any(Runnable.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        delayedCallbacks.forEach(TransactionSynchronization::afterCommit);

        verify(agentRuntimeDao).findByAgentId("agent-001");
        verify(eventPublisher, never()).publishAgentStatus(any(), any(), any());
        verify(eventPublisher, never()).publishCapabilityIndex(any(), any(), any());
        verify(agentRuntimeDao, never()).findRosterByOwner(any(), any(), any(), any());
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
        ArgumentCaptor<AgentTaskEventWriteCommand> persistent =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(taskEventWriter).append(persistent.capture());
        assertEquals(TaskEventType.TASK_CREATED, persistent.getValue().getEventType());
        assertEquals(TaskEventType.ActorType.SYSTEM, persistent.getValue().getActorType());
    }

    @Test
    void createReservesScopedRootBeforeTaskPlanAndPublishesOnlyAfterCommit() {
        when(taskServiceProvider.getIfAvailable()).thenReturn(taskService);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        doAnswer(invocation -> {
            invocation.<TaskPlanEntity>getArgument(0).setId(42L);
            return invocation.getArgument(0);
        }).when(taskService).create(any(TaskPlanEntity.class));
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("root first");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AgentTaskDTO result = agentService.createTask(request);

            InOrder order = inOrder(agentTaskMetaDao, taskService, taskEventWriter);
            order.verify(agentTaskMetaDao).insert(any(AgentTaskMetaEntity.class));
            order.verify(taskService).create(any(TaskPlanEntity.class));
            order.verify(taskEventWriter).append(any());
            verify(agentTaskMetaDao).rekeyReservedTaskRoot(
                    eq("juyiting"), eq("jia_client"), any(), eq("42"), anyLong());
            assertEquals("42", result.getId());
            verify(eventPublisher, never()).publishTaskEvent(any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(eventPublisher).publishTaskEvent(eq("task_created"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void createPlanIdCollisionFailsClosedBeforeEventOrPublisher() {
        when(taskServiceProvider.getIfAvailable()).thenReturn(taskService);
        doAnswer(invocation -> {
            invocation.<TaskPlanEntity>getArgument(0).setId(42L);
            return invocation.getArgument(0);
        }).when(taskService).create(any(TaskPlanEntity.class));
        AgentTaskMetaEntity existing = new AgentTaskMetaEntity()
                .setId(42L).setTaskId("42")
                .setRewardStatus(AgentConstants.TASK_STATUS_OPEN)
                .setTaskVersion(9L).setCurrentEventVersion(7L);
        existing.setTenantId("juyiting");
        existing.setClientId("jia_client");
        when(agentTaskMetaDao.findByTaskIdForUpdate("juyiting", "jia_client", "42"))
                .thenReturn(existing);
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("collision");

        assertThrows(IllegalStateException.class, () -> agentService.createTask(request));

        verify(agentTaskMetaDao, never()).deleteReservedTaskRoot(any(), any(), any());
        verify(agentTaskMetaDao, never()).rekeyReservedTaskRoot(
                any(), any(), any(), any(), anyLong());
        verify(taskEventWriter, never()).append(any());
        verify(eventPublisherProvider, never()).getIfAvailable();
    }

    @Test
    void duplicateCreateRootIsNoOpAndAllocatesNoPersistentEvent() {
        org.mockito.Mockito.doAnswer(invocation -> {
            String reservedTaskId = invocation.getArgument(2);
            AgentTaskMetaEntity existing = new AgentTaskMetaEntity()
                    .setId(7L).setTaskId(reservedTaskId)
                    .setRewardStatus(AgentConstants.TASK_STATUS_OPEN)
                    .setTaskVersion(0L).setCurrentEventVersion(4L);
            existing.setTenantId("juyiting");
            existing.setClientId("jia_client");
            AgentTaskMutationTransaction.ReservedTaskMutation<?> mutation =
                    invocation.getArgument(4);
            return mutation.apply(existing, false);
        }).when(mutationTransaction).executeAfterTaskRootReservation(
                any(), any(), any(), any(), any());

        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("duplicate");
        AgentTaskDTO result = agentService.createTask(request);

        assertEquals("duplicate", result.getTitle());
        verify(taskService, never()).create(any());
        verify(taskEventWriter, never()).append(any());
        verify(eventPublisherProvider, never()).getIfAvailable();
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
    void writableTaskMembersExcludeInvitedAndTerminalReadOnlyStatuses() {
        AgentTaskMetaEntity task = scopedTaskRow("task-write", "tenant-a", "client-a");
        when(agentTaskMetaDao.findByTaskId("tenant-a", "client-a", "task-write"))
                .thenReturn(task);
        when(agentTaskMemberDao.listByTask("tenant-a", "client-a", "task-write"))
                .thenReturn(List.of(
                        taskMember("tenant-a", "client-a", "task-write", "accepted", "accepted"),
                        taskMember("tenant-a", "client-a", "task-write", "working", "working"),
                        taskMember("tenant-a", "client-a", "task-write", "blocked", "blocked"),
                        taskMember("tenant-a", "client-a", "task-write", "invited", "invited"),
                        taskMember("tenant-a", "client-a", "task-write", "done", "done"),
                        taskMember("tenant-a", "client-a", "task-write", "failed", "failed")));

        assertEquals(List.of("accepted", "working", "blocked"),
                agentService.listTaskWritableMemberAgentIds(
                        "tenant-a", "client-a", "task-write"));
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
                eq("juyiting"), eq("jia_client"), eq("task-001"),
                eq(List.of("agent-wuyong", "agent-linchong")), eq(false),
                any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class));
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
                new AgentSceneFeatureFlags(false, true), mutationTransaction, taskEventWriter);
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
                    eq("juyiting"), eq("jia_client"), eq("task-001"), eq(List.of("agent-wuyong")), eq(false),
                    any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class));
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
    void canaryAssignmentUsesDurableInviteWithoutLegacyAgentActionDoubleSend() {
        agentService = new AgentServiceImpl(
                agentRuntimeDao, agentIdentityService, agentPersonaDao,
                agentPersonaBindingDao, agentTaskMetaDao, agentTaskMemberDao,
                legacyTaskCompatibilityService, agentTaskNoteDao, dialogueTemplateDao,
                eventPublisherProvider, taskServiceProvider, apiKeyServiceProvider,
                sceneServiceProvider, scopePublicationCoordinator,
                new AgentSceneFeatureFlags(true, true), mutationTransaction,
                taskEventWriter, commandTransportCapture);
        AgentRuntimeEntity agent = ownedAgent(
                "agent-wuyong", "Wu Yong", AgentConstants.STATUS_ONLINE,
                "[\"planning\"]");
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(agent);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId(
                "juyiting", "jia_client", "task-001")).thenReturn(meta);
        when(commandTransportCapture.captureTaskInvites(
                any(), any(), eq("evt-task-assigned"), eq(1_000L)))
                .thenReturn(true);
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId("agent-wuyong");

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        verify(commandTransportCapture).captureTaskInvites(
                eq(result), any(), eq("evt-task-assigned"), eq(1_000L));
        verify(eventPublisher, never()).publishAgentAction(any());
        verify(eventPublisher).publishTaskEvent("task_assigned", result);
        assertTrue(result.getActionDispatchResults().isEmpty());
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
    void personaCatalogDisclosesOnlyExactOwnerBindingAndTreatsForeignOwnerAsUnbound() {
        AgentPersonaEntity persona = persona("lujunyi", "卢俊义", "玉麒麟");
        persona.setTenantId("0");
        when(agentPersonaDao.findCatalogProjection("juyiting", "jia_client"))
                .thenReturn(List.of(persona));
        when(agentPersonaBindingDao.findCatalogOverlay(
                "juyiting", "jia_client", "juyiting")).thenReturn(List.of());

        List<AgentRuntimeDTO> result = agentService.listPersonaCatalog(
                "juyiting", "jia_client", "juyiting");

        assertEquals(1, result.size());
        AgentRuntimeDTO dto = result.getFirst();
        assertEquals("lujunyi", dto.getPersonaCode());
        assertNull(dto.getAgentId());
        assertNull(dto.getOwnerJiacn());
        assertFalse(dto.getBound());
        assertFalse(dto.getBoundToMe());
        assertTrue(dto.getCanBind());
        assertEquals(AgentConstants.STATUS_OFFLINE, dto.getStatus());
        verify(agentPersonaBindingDao).findCatalogOverlay(
                "juyiting", "jia_client", "juyiting");
        verify(agentPersonaBindingDao, never()).findActiveByClientAndPersona(any(), any());
        verify(agentIdentityService, never()).requireRegistrationIdentityInScope(any(), any(), any(), any());
        verify(agentRuntimeDao, never()).findByAgentId(any());
    }

    @Test
    void personaCatalogExactOwnerBindingIsBoundAndCannotBindAgain() {
        AgentPersonaEntity persona = persona("lujunyi", "卢俊义", "玉麒麟");
        persona.setTenantId("0");
        AgentPersonaBindingEntity binding = binding(
                "agt_0123456789abcdef0123456789abcdef", "lujunyi");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_ACTIVE);
        AgentRuntimeEntity runtime = runtimeAgent(
                identity.getCanonicalAgentId(), "卢俊义", AgentConstants.STATUS_ONLINE,
                "[\"runtime-skill\"]");
        runtime.setTenantId("juyiting");
        runtime.setClientId("jia_client");
        runtime.setOwnerJiacn("juyiting");
        runtime.setBindingId(binding.getId());
        when(agentPersonaDao.findCatalogProjection("juyiting", "jia_client"))
                .thenReturn(List.of(persona));
        when(agentPersonaBindingDao.findCatalogOverlay(
                "juyiting", "jia_client", "juyiting"))
                .thenReturn(List.of(catalogOverlay(binding, identity, runtime)));

        AgentRuntimeDTO dto = agentService.listPersonaCatalog(
                "juyiting", "jia_client", "juyiting").getFirst();

        assertEquals(identity.getCanonicalAgentId(), dto.getAgentId());
        assertEquals("juyiting", dto.getOwnerJiacn());
        assertTrue(dto.getBound());
        assertTrue(dto.getBoundToMe());
        assertFalse(dto.getCanBind());
        assertTrue(dto.getCanOperate());
        assertEquals(List.of("runtime-skill"), dto.getAbilities());
        verify(agentIdentityService, never()).requireRegistrationIdentityInScope(any(), any(), any(), any());
        verify(agentRuntimeDao, never()).findByAgentId(any());
    }

    @Test
    void personaCatalogCachesOnlyMetadataAndOverlaysFreshBindingStateOnEveryRequest() {
        AgentPersonaEntity persona = persona("linchong", "林冲", "豹子头");
        persona.setTenantId("0");
        persona.setPower(88);
        AgentPersonaBindingEntity binding = binding(
                "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "linchong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_ACTIVE);
        when(agentPersonaDao.findCatalogProjection("juyiting", "jia_client"))
                .thenReturn(List.of(persona));
        when(agentPersonaBindingDao.findCatalogOverlay(
                "juyiting", "jia_client", "juyiting"))
                .thenReturn(List.of(), List.of(catalogOverlay(binding, identity, null)));

        AgentRuntimeDTO first = agentService.listPersonaCatalog(
                "juyiting", "jia_client", "juyiting").getFirst();
        first.setName("poisoned");
        first.setAbilities(List.of("poisoned"));
        first.getStats().setPower(999);
        AgentRuntimeDTO second = agentService.listPersonaCatalog(
                "juyiting", "jia_client", "juyiting").getFirst();

        assertEquals("林冲", second.getName());
        assertEquals(List.of("planning", "research"), second.getAbilities());
        assertEquals(88, second.getStats().getPower());
        assertFalse(first.getBound());
        assertTrue(second.getBound());
        assertEquals(identity.getCanonicalAgentId(), second.getAgentId());
        verify(agentPersonaDao, times(1)).findCatalogProjection("juyiting", "jia_client");
        verify(agentPersonaBindingDao, times(2)).findCatalogOverlay(
                "juyiting", "jia_client", "juyiting");
    }

    @Test
    void personaCatalogFailsClosedOnCrossScopeOrIncompleteUserOverlay() {
        AgentPersonaEntity persona = persona("linchong", "林冲", "豹子头");
        persona.setTenantId("0");
        AgentPersonaBindingEntity binding = binding(
                "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "linchong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_ACTIVE);
        AgentPersonaCatalogBindingRow escaped = catalogOverlay(binding, identity, null);
        escaped.setIdentityClientId("Client-A");
        when(agentPersonaDao.findCatalogProjection("juyiting", "jia_client"))
                .thenReturn(List.of(persona));
        when(agentPersonaBindingDao.findCatalogOverlay(
                "juyiting", "jia_client", "juyiting")).thenReturn(List.of(escaped));

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> agentService.listPersonaCatalog("juyiting", "jia_client", "juyiting"));
    }

    @Test
    void personaCatalogFailsClosedOnUnknownBindingPersonaOrInvalidRuntimeStatus() {
        AgentPersonaEntity persona = persona("linchong", "林冲", "豹子头");
        persona.setTenantId("0");
        AgentPersonaBindingEntity binding = binding(
                "agt_cccccccccccccccccccccccccccccccc", "wuyong");
        AgentIdentityRegistryEntity identity = identity(binding, AgentConstants.IDENTITY_STATUS_ACTIVE);
        AgentPersonaCatalogBindingRow unknownPersona = catalogOverlay(binding, identity, null);
        when(agentPersonaDao.findCatalogProjection("juyiting", "jia_client"))
                .thenReturn(List.of(persona));
        when(agentPersonaBindingDao.findCatalogOverlay(
                "juyiting", "jia_client", "juyiting")).thenReturn(List.of(unknownPersona));

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> agentService.listPersonaCatalog("juyiting", "jia_client", "juyiting"));

        AgentRuntimeEntity runtime = runtimeAgent(
                identity.getCanonicalAgentId(), "吴用", "tampered", "[]");
        runtime.setTenantId("juyiting");
        runtime.setClientId("jia_client");
        runtime.setOwnerJiacn("juyiting");
        runtime.setBindingId(binding.getId());
        binding.setPersonaCode("linchong");
        AgentPersonaCatalogBindingRow invalidRuntime = catalogOverlay(binding, identity, runtime);
        when(agentPersonaBindingDao.findCatalogOverlay(
                "juyiting", "jia_client", "juyiting")).thenReturn(List.of(invalidRuntime));

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> agentService.listPersonaCatalog("juyiting", "jia_client", "juyiting"));
    }

    @Test
    void newPersonaBindingIssuesOpaqueIdentityInsteadOfLegacyJytId() {
        AgentPersonaEntity persona = persona("wuyong", "吴用", "智多星");
        when(agentPersonaDao.findByCode("wuyong")).thenReturn(persona);

        AgentRuntimeDTO result = agentService.bindPersona("juyiting", "jia_client", "juyiting", "wuyong");

        assertTrue(result.getAgentId().matches("agt_[0-9a-f]{32}"));
        assertTrue(!result.getAgentId().startsWith("jyt-"));
        ArgumentCaptor<AgentPersonaBindingEntity> bindingCaptor =
                ArgumentCaptor.forClass(AgentPersonaBindingEntity.class);
        verify(agentPersonaBindingDao).findExactActiveByScopeAndPersonaForUpdate(
                "juyiting", "jia_client", "juyiting", "wuyong");
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
    void recommendTaskAssigneesReturnsEligibleScoresAndExcludedCandidates() {
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

        AgentRuntimeEntity planner = ownedAgent("agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE,
                "[\"planning\",\"analysis\"]");
        AgentRuntimeEntity executor = ownedAgent("agent-linchong", "林冲", AgentConstants.STATUS_ONLINE,
                "[\"execution\"]");
        AgentRuntimeEntity busyPlanner = ownedAgent("agent-busy", "忙碌好汉", AgentConstants.STATUS_BUSY,
                "[\"planning\"]");
        AgentRuntimeEntity offlinePlanner = ownedAgent("agent-offline", "离线好汉", AgentConstants.STATUS_OFFLINE,
                "[\"planning\"]");
        AgentRuntimeEntity erroredPlanner = ownedAgent("agent-error", "故障好汉", AgentConstants.STATUS_ERROR,
                "[\"planning\"]");
        when(agentRuntimeDao.findCandidateRosterByOwner("jia_client", "juyiting"))
                .thenReturn(List.of(executor, offlinePlanner, erroredPlanner, busyPlanner, planner));

        List<AgentTaskRecommendationDTO> result = agentService.recommendTaskAssignees("task-001");

        assertEquals(5, result.size());
        AgentTaskRecommendationDTO selected = result.getFirst();
        assertEquals("agent-wuyong", selected.getAgent().getAgentId());
        assertTrue(selected.getEligible());
        assertEquals(List.of(), selected.getExclusionReasons());
        assertEquals(Map.of("ability", 40, "availability", 20, "success", 11,
                "load", 15, "context", 8, "riskPenalty", 0), selected.getScoreParts());
        assertEquals(94, selected.getScore());
        assertEquals(100, selected.getAbilityScore());
        assertTrue(selected.getReason().contains("宋江首领建议"));

        AgentTaskRecommendationDTO busy = result.stream()
                .filter(candidate -> "agent-busy".equals(candidate.getAgent().getAgentId()))
                .findFirst().orElseThrow();
        assertFalse(busy.getEligible());
        assertEquals(List.of(AgentErrorConstants.AGENT_BUSY), busy.getExclusionReasons());
        AgentTaskRecommendationDTO unmatched = result.stream()
                .filter(candidate -> "agent-linchong".equals(candidate.getAgent().getAgentId()))
                .findFirst().orElseThrow();
        assertFalse(unmatched.getEligible());
        assertEquals(List.of(AgentErrorConstants.AGENT_ABILITY_MISMATCH), unmatched.getExclusionReasons());
        AgentTaskRecommendationDTO offline = result.stream()
                .filter(candidate -> "agent-offline".equals(candidate.getAgent().getAgentId()))
                .findFirst().orElseThrow();
        assertFalse(offline.getEligible());
        assertEquals(List.of(AgentErrorConstants.AGENT_OFFLINE), offline.getExclusionReasons());
        AgentTaskRecommendationDTO errored = result.stream()
                .filter(candidate -> "agent-error".equals(candidate.getAgent().getAgentId()))
                .findFirst().orElseThrow();
        assertFalse(errored.getEligible());
        assertEquals(List.of(AgentErrorConstants.AGENT_ERROR), errored.getExclusionReasons());
        verify(agentRuntimeDao).findCandidateRosterByOwner("jia_client", "juyiting");
        verify(agentRuntimeDao, never()).findRosterByOwner("jia_client", "juyiting", null, null);
    }

    @Test
    void recommendTaskAssigneesPreservesRosterOrderWhenEligibilityAndScoresTie() {
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
        AgentRuntimeEntity planner = ownedAgent("agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE,
                "[\"planning\"]");
        AgentRuntimeEntity executor = ownedAgent("agent-linchong", "林冲", AgentConstants.STATUS_ONLINE,
                "[\"execution\"]");
        when(agentRuntimeDao.findCandidateRosterByOwner("jia_client", "juyiting"))
                .thenReturn(List.of(planner, executor));

        List<AgentTaskRecommendationDTO> result = agentService.recommendTaskAssignees("task-001");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(candidate -> Boolean.TRUE.equals(candidate.getEligible())));
        assertEquals(result.get(0).getScore(), result.get(1).getScore());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), result.stream()
                .map(candidate -> candidate.getAgent().getAgentId()).toList());
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
        when(agentRuntimeDao.findCandidateRosterByOwner("jia_client", "juyiting")).thenReturn(List.of(planner, executor));
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(planner);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(executor);

        AgentTaskDTO result = agentService.autoAssignTask("task-001", new AgentTaskAssignDTO());

        assertEquals(AgentConstants.TASK_STATUS_ASSIGNED, result.getStatus());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), result.getAssignedAgentIds());
        verify(agentTaskMetaDao, never()).updateById(meta);
        verify(legacyTaskCompatibilityService).assignResolved(
                eq("juyiting"), eq("jia_client"), eq("task-001"),
                eq(List.of("agent-wuyong", "agent-linchong")), eq(true),
                any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class));
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
        when(agentRuntimeDao.findCandidateRosterByOwner("jia_client", "juyiting"))
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
                eq("juyiting"), eq("jia_client"), eq("task-001"),
                eq(List.of("agent-wuyong", "agent-linchong")), eq(true),
                any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class));
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
        when(agentRuntimeDao.findCandidateRosterByOwner("jia_client", "juyiting"))
                .thenReturn(List.of(planner));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> agentService.autoAssignTask("task-001", new AgentTaskAssignDTO()));

        assertEquals("No available agent can accept this task", failure.getMessage());
        verify(legacyTaskCompatibilityService, never()).assignResolved(
                any(), any(), any(), any(), anyBoolean(),
                any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class));
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
        verify(agentTaskMetaDao).updateStatusByVersion(
                "juyiting", "jia_client", "task-001", 2L,
                AgentConstants.TASK_STATUS_ARCHIVED, null, null, null);
        verify(agentTaskMetaDao, never()).updateById(any());
        verify(agentRuntimeDao, never()).updateById(any());
        verify(eventPublisher).publishTaskEvent(eq("task_archived"), any(AgentTaskDTO.class));
        ArgumentCaptor<AgentTaskEventWriteCommand> persistent =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(taskEventWriter).append(persistent.capture());
        assertEquals(TaskEventType.TASK_ARCHIVED, persistent.getValue().getEventType());
        ArgumentCaptor<AgentSceneStateDTO> stateCaptor = ArgumentCaptor.forClass(AgentSceneStateDTO.class);
        verify(sceneService).upsertState(eq("juyiting-main"), stateCaptor.capture());
        AgentSceneStateDTO state = stateCaptor.getValue();
        assertEquals("returning_home", state.getBehavior());
        assertEquals("main-seat", state.getTargetRegionId());
        assertEquals("task-001", state.getRelatedId());
        assertTrue(state.getExpiresAt() > state.getExpectedArrivalAt());
    }

    @Test
    void archiveUsesTerminalStateMachineCasVersionedEventAndAfterCommitNotification() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_FAILED);
        meta.setTaskVersion(5L);
        meta.setCurrentEventVersion(2L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AgentTaskDTO result = agentService.archiveTask("task-001");

            assertEquals(AgentConstants.TASK_STATUS_ARCHIVED, result.getStatus());
            assertEquals(6L, meta.getTaskVersion());
            verify(agentTaskMetaDao).updateStatusByVersion(
                    "juyiting", "jia_client", "task-001", 5L,
                    AgentConstants.TASK_STATUS_ARCHIVED, null, null, null);
            verify(agentTaskMetaDao, never()).updateById(any());
            ArgumentCaptor<AgentTaskEventWriteCommand> event =
                    ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
            verify(taskEventWriter).append(event.capture());
            Map<String, Object> payload = JsonUtil.jsonToMap(event.getValue().getEventJson());
            assertEquals(5L, ((Number) payload.get("expectedVersion")).longValue());
            assertEquals(6L, ((Number) payload.get("resultVersion")).longValue());
            verify(eventPublisher, never()).publishTaskEvent(any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(eventPublisher).publishTaskEvent(eq("task_archived"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void archiveAllowsCredentialLikeTaskIdWithoutLeakingFailureSecret() {
        String taskId = "task-authorization-api_key-api-key";
        String rawFailure = "Authorization: Bearer archive-secret";
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId(taskId);
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_FAILED);
        meta.setFailureReason(rawFailure);
        meta.setTaskVersion(5L);
        meta.setCurrentEventVersion(2L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", taskId))
                .thenReturn(meta);

        AgentTaskDTO result = agentService.archiveTask(taskId);

        assertEquals(AgentConstants.TASK_STATUS_ARCHIVED, result.getStatus());
        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(taskEventWriter).append(event.capture());
        assertTrue(event.getValue().getEventJson().contains(taskId));
        assertFalse(event.getValue().getEventJson().contains(rawFailure));
        assertFalse(event.getValue().getEventJson().contains("archive-secret"));
    }

    @Test
    void archiveRejectsMaxTaskVersionBeforeCasEventOrPublisher() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-max-version");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        meta.setTaskVersion(Long.MAX_VALUE);
        meta.setCurrentEventVersion(2L);
        when(agentTaskMetaDao.findByTaskId(
                "juyiting", "jia_client", "task-max-version")).thenReturn(meta);

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> agentService.archiveTask("task-max-version"));

        verify(agentTaskMetaDao, never()).updateStatusByVersion(
                any(), any(), any(), anyLong(), any(), any(), any(), any());
        verify(taskEventWriter, never()).append(any());
        verify(eventPublisherProvider, never()).getIfAvailable();
    }

    @Test
    void archiveRejectsNonterminalStatesAndCasConflictWithoutEventOrNotification() {
        for (String status : List.of(
                AgentConstants.TASK_STATUS_OPEN, AgentConstants.TASK_STATUS_ASSIGNED,
                AgentConstants.TASK_STATUS_RUNNING, "INVALID")) {
            org.mockito.Mockito.clearInvocations(
                    agentTaskMetaDao, taskEventWriter, eventPublisher, eventPublisherProvider);
            AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
            meta.setId(1L);
            meta.setTaskId("task-001");
            meta.setTenantId("juyiting");
            meta.setClientId("jia_client");
            meta.setRewardStatus(status);
            meta.setTaskVersion(3L);
            when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                    .thenReturn(meta);

            assertThrows(AgentServiceImpl.AgentBizException.class,
                    () -> agentService.archiveTask("task-001"));
            verify(agentTaskMetaDao, never()).updateStatusByVersion(
                    any(), any(), any(), anyLong(), any(), any(), any(), any());
            verify(taskEventWriter, never()).append(any());
            verify(eventPublisher, never()).publishTaskEvent(any(), any());
        }

        org.mockito.Mockito.clearInvocations(
                agentTaskMetaDao, taskEventWriter, eventPublisher, eventPublisherProvider);
        AgentTaskMetaEntity terminal = new AgentTaskMetaEntity();
        terminal.setId(1L);
        terminal.setTaskId("task-001");
        terminal.setTenantId("juyiting");
        terminal.setClientId("jia_client");
        terminal.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        terminal.setTaskVersion(3L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(terminal);
        when(agentTaskMetaDao.updateStatusByVersion(
                "juyiting", "jia_client", "task-001", 3L,
                AgentConstants.TASK_STATUS_ARCHIVED, null, null, null)).thenReturn(0);

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> agentService.archiveTask("task-001"));
        verify(taskEventWriter, never()).append(any());
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
    }

    @Test
    void addTaskNoteStoresNoteAndListTaskNotesReturnsSavedNotes() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        meta.setTaskVersion(0L);
        meta.setCurrentEventVersion(0L);
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
        ArgumentCaptor<AgentTaskEventWriteCommand> persistent =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(taskEventWriter).append(persistent.capture());
        assertEquals(TaskEventType.PROGRESS_REPORTED, persistent.getValue().getEventType());
        assertTrue(persistent.getValue().getEventJson().contains("contentSha256"));
        assertTrue(persistent.getValue().getEventJson().contains("contentByteLength"));
        assertTrue(!persistent.getValue().getEventJson().contains(request.getContent()));

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
    void repeatedNotesUseDistinctEventIdsAndOnlyFrozenPayloadKeys() throws Exception {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity()
                .setId(1L).setTaskId("task-001")
                .setTaskVersion(0L).setCurrentEventVersion(0L);
        meta.setTenantId("juyiting");
        meta.setClientId("jia_client");
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001"))
                .thenReturn(meta);
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong();
        when(agentTaskNoteDao.insert(any(AgentTaskNoteEntity.class))).thenAnswer(invocation -> {
            invocation.<AgentTaskNoteEntity>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        });

        for (String content : List.of("first secret note", "second secret note")) {
            AgentTaskNoteDTO note = new AgentTaskNoteDTO();
            note.setNoteType("summary");
            note.setContent(content);
            agentService.addTaskNote("task-001", note);
        }

        ArgumentCaptor<AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(taskEventWriter, times(2)).append(events.capture());
        assertTrue(!events.getAllValues().get(0).getEventId()
                .equals(events.getAllValues().get(1).getEventId()));
        for (AgentTaskEventWriteCommand event : events.getAllValues()) {
            Map<?, ?> payload = JsonUtil.getMapper().readValue(event.getEventJson(), Map.class);
            assertEquals(Set.of(
                            TaskEventPayload.Key.NOTE_ID,
                            TaskEventPayload.Key.NOTE_TYPE,
                            TaskEventPayload.Key.CONTENT_SHA256,
                            TaskEventPayload.Key.CONTENT_BYTE_LENGTH),
                    payload.keySet());
            assertTrue(!event.getEventJson().contains("secret note"));
        }
    }

    @Test
    void searchTasksUsesAuthenticatedTenantAndClientForEachExactScope() {
        AgentTaskSearchRow firstScope = searchRow(
                "task-upper", "Tenant-A", "Client-A", AgentConstants.TASK_STATUS_OPEN);
        AgentTaskSearchRow secondScope = searchRow(
                "task-lower", "tenant-a", "client-a", AgentConstants.TASK_STATUS_RUNNING);

        when(agentTaskMetaDao.countSearch("Tenant-A", "Client-A", null, null, null))
                .thenReturn(1L);
        when(agentTaskMetaDao.searchPage(
                "Tenant-A", "Client-A", null, null, null, 0L, 20))
                .thenReturn(List.of(firstScope));
        when(agentTaskMetaDao.countSearch("tenant-a", "client-a", null, null, null))
                .thenReturn(1L);
        when(agentTaskMetaDao.searchPage(
                "tenant-a", "client-a", null, null, null, 0L, 20))
                .thenReturn(List.of(secondScope));

        authenticateTaskScope("Tenant-A", "Client-A");
        PageInfo<AgentTaskDTO> first = agentService.searchTasks(new AgentTaskSearchDTO());
        authenticateTaskScope("tenant-a", "client-a");
        PageInfo<AgentTaskDTO> second = agentService.searchTasks(new AgentTaskSearchDTO());

        assertEquals(List.of("task-upper"),
                first.getList().stream().map(AgentTaskDTO::getId).toList());
        assertEquals(List.of("task-lower"),
                second.getList().stream().map(AgentTaskDTO::getId).toList());
        verify(agentTaskMetaDao).searchPage(
                "Tenant-A", "Client-A", null, null, null, 0L, 20);
        verify(agentTaskMetaDao).searchPage(
                "tenant-a", "client-a", null, null, null, 0L, 20);
    }

    @Test
    void countTasksByStatusUsesAuthenticatedTenantAndClientForEachExactScope() {
        when(agentTaskMetaDao.countSearchByStatus(
                "Tenant-A", "Client-A", null, null))
                .thenReturn(List.of(statusCount("Tenant-A", "Client-A",
                        AgentConstants.TASK_STATUS_ASSIGNED, 1L)));
        when(agentTaskMetaDao.countSearchByStatus(
                "tenant-a", "client-a", null, null))
                .thenReturn(List.of(statusCount("tenant-a", "client-a",
                        AgentConstants.TASK_STATUS_COMPLETED, 1L)));

        authenticateTaskScope("Tenant-A", "Client-A");
        Map<String, Long> first = agentService.countTasksByStatus(new AgentTaskSearchDTO());
        authenticateTaskScope("tenant-a", "client-a");
        Map<String, Long> second = agentService.countTasksByStatus(new AgentTaskSearchDTO());

        assertEquals(1L, first.get("total"));
        assertEquals(1L, first.get(AgentConstants.TASK_STATUS_ASSIGNED));
        assertEquals(0L, first.get(AgentConstants.TASK_STATUS_COMPLETED));
        assertEquals(1L, second.get("total"));
        assertEquals(0L, second.get(AgentConstants.TASK_STATUS_ASSIGNED));
        assertEquals(1L, second.get(AgentConstants.TASK_STATUS_COMPLETED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTaskScopeAuthentications")
    void taskSearchAndCountsRejectInvalidAuthenticationBeforeDao(
            String caseName, Authentication authentication) {
        EsContext poisoned = new EsContext();
        poisoned.setJiacn("cookie-tenant");
        poisoned.setClientId("cookie-client");
        EsContextHolder.setContext(poisoned);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        AgentServiceImpl.AgentBizException searchFailure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.searchTasks(new AgentTaskSearchDTO()));
        AgentServiceImpl.AgentBizException countFailure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.countTasksByStatus(new AgentTaskSearchDTO()));

        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, searchFailure.getCode());
        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, countFailure.getCode());
        verify(agentTaskMetaDao, never()).countSearch(any(), any(), any(), any(), any());
        verify(agentTaskMetaDao, never()).countSearchByStatus(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "searchTasks rejects {0}")
    @MethodSource("contaminatedTaskRows")
    void searchTasksRejectsWholeResultForContaminatedDaoRow(
            String caseName, String rowTenantId, String rowClientId) {
        AgentTaskSearchRow valid = searchRow(
                "valid-task", "tenant-a", "client-a", AgentConstants.TASK_STATUS_OPEN);
        AgentTaskSearchRow contaminated = searchRow(
                "contaminated-task", rowTenantId, rowClientId, AgentConstants.TASK_STATUS_OPEN);
        when(agentTaskMetaDao.countSearch("tenant-a", "client-a", null, null, null))
                .thenReturn(2L);
        when(agentTaskMetaDao.searchPage(
                "tenant-a", "client-a", null, null, null, 0L, 20))
                .thenReturn(List.of(valid, contaminated));
        authenticateTaskScope("tenant-a", "client-a");

        assertThrows(IllegalArgumentException.class,
                () -> agentService.searchTasks(new AgentTaskSearchDTO()));
        verify(agentTaskMetaDao, never()).findSearchMembers(any(), any(), any());
    }

    @ParameterizedTest(name = "countTasksByStatus rejects {0}")
    @MethodSource("contaminatedTaskRows")
    void countTasksByStatusRejectsWholeResultForContaminatedDaoRow(
            String caseName, String rowTenantId, String rowClientId) {
        when(agentTaskMetaDao.countSearchByStatus(
                "tenant-a", "client-a", null, null))
                .thenReturn(List.of(
                        statusCount("tenant-a", "client-a", AgentConstants.TASK_STATUS_OPEN, 1L),
                        statusCount(rowTenantId, rowClientId, AgentConstants.TASK_STATUS_RUNNING, 1L)));
        authenticateTaskScope("tenant-a", "client-a");

        assertThrows(IllegalArgumentException.class,
                () -> agentService.countTasksByStatus(new AgentTaskSearchDTO()));
    }

    @Test
    void searchTasksRejectsContaminatedMemberAndRuntimeBatchRows() {
        AgentTaskSearchRow task = searchRow(
                "task-1", "tenant-a", "client-a", AgentConstants.TASK_STATUS_OPEN);
        when(agentTaskMetaDao.countSearch("tenant-a", "client-a", null, null, null))
                .thenReturn(1L);
        when(agentTaskMetaDao.searchPage(
                "tenant-a", "client-a", null, null, null, 0L, 20))
                .thenReturn(List.of(task));
        when(agentTaskMetaDao.findSearchMembers(
                "tenant-a", "client-a", List.of("task-1")))
                .thenReturn(List.of(taskMember(
                        "tenant-b", "client-a", "task-1", "agent-a", "accepted")));
        authenticateTaskScope("tenant-a", "client-a");

        assertThrows(IllegalArgumentException.class,
                () -> agentService.searchTasks(new AgentTaskSearchDTO()));
        verify(agentTaskMetaDao, never()).findSearchRuntimes(any());

        org.mockito.Mockito.reset(agentTaskMetaDao);
        when(agentTaskMetaDao.countSearch("tenant-a", "client-a", null, null, null))
                .thenReturn(1L);
        when(agentTaskMetaDao.searchPage(
                "tenant-a", "client-a", null, null, null, 0L, 20))
                .thenReturn(List.of(task));
        when(agentTaskMetaDao.findSearchMembers(
                "tenant-a", "client-a", List.of("task-1")))
                .thenReturn(List.of(taskMember(
                        "tenant-a", "client-a", "task-1", "agent-a", "accepted")));
        when(agentTaskMetaDao.findSearchRuntimes(List.of("agent-a")))
                .thenReturn(List.of(runtimeAgent(
                        "agent-b", "foreign", AgentConstants.STATUS_ONLINE, "[]")));

        assertThrows(IllegalArgumentException.class,
                () -> agentService.searchTasks(new AgentTaskSearchDTO()));
    }

    @Test
    void searchTasksRejectsCorruptFundingProjectionWithoutSensitiveFallbackQueries() {
        AgentTaskSearchRow task = searchRow(
                "task-funded", "tenant-a", "client-a", AgentConstants.TASK_STATUS_OPEN);
        task.setFundingPresent(1);
        task.setFundingProjectionValid(0);
        task.setFundingMode("FUNDED_SINGLE_AGENT");
        task.setFundingStatus("FUNDS_HELD");
        task.setEscrowId("escrow-1");
        task.setGrossBountyAmountMicro(10L);
        task.setRemainingMicro(10L);
        task.setRequiredSkillRequirements("[]");
        when(agentTaskMetaDao.countSearch("tenant-a", "client-a", null, null, null))
                .thenReturn(1L);
        when(agentTaskMetaDao.searchPage(
                "tenant-a", "client-a", null, null, null, 0L, 20))
                .thenReturn(List.of(task));
        authenticateTaskScope("tenant-a", "client-a");

        assertThrows(IllegalArgumentException.class,
                () -> agentService.searchTasks(new AgentTaskSearchDTO()));
        verify(taskServiceProvider, never()).getIfAvailable();
        verify(agentTaskMemberDao, never()).listByTask(any(), any(), any());
    }

    @Test
    void countTasksByStatusIgnoresSelectedStatusAndPushesAbilityAndKeywordToAggregation() {
        when(agentTaskMetaDao.countSearchByStatus(
                "juyiting", "jia_client", "planning", "reward"))
                .thenReturn(List.of(
                        statusCount("juyiting", "jia_client",
                                AgentConstants.TASK_STATUS_ASSIGNED, 1L),
                        statusCount("juyiting", "jia_client",
                                AgentConstants.TASK_STATUS_RUNNING, 1L)));
        authenticateTaskScope("juyiting", "jia_client");

        AgentTaskSearchDTO request = new AgentTaskSearchDTO();
        request.setStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        request.setAbility("planning");
        request.setKeyword("reward");

        Map<String, Long> counts = agentService.countTasksByStatus(request);

        assertEquals(2L, counts.get("total"));
        assertEquals(1L, counts.get(AgentConstants.TASK_STATUS_ASSIGNED));
        assertEquals(1L, counts.get(AgentConstants.TASK_STATUS_RUNNING));
        assertEquals(0L, counts.get(AgentConstants.TASK_STATUS_COMPLETED));
        verify(agentTaskMetaDao).countSearchByStatus(
                "juyiting", "jia_client", "planning", "reward");
    }

    @Test
    void searchTasksUsesDatabaseFilteringPaginationAndFourQueryUpperBound() {
        AgentTaskSearchRow first = searchRow(
                "1", "juyiting", "jia_client", AgentConstants.TASK_STATUS_OPEN);
        first.setPlanTitle("夜探祝家庄榜文");
        first.setPlanDescription("先探路，再回厅前公议");
        first.setRequiredAbilities("[\"planning\"]");
        AgentTaskSearchRow second = searchRow(
                "2", "juyiting", "jia_client", AgentConstants.TASK_STATUS_OPEN);
        second.setPlanTitle("巡山榜文");
        second.setRequiredAbilities("[\"planning\"]");

        AgentTaskMemberEntity memberOne = taskMember(
                "juyiting", "jia_client", "1", "agent-wuyong", "accepted");
        AgentTaskMemberEntity memberTwo = taskMember(
                "juyiting", "jia_client", "2", "agent-linchong", "working");
        AgentRuntimeEntity wuYong = runtimeAgent(
                "agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE, "[]");
        AgentRuntimeEntity linChong = runtimeAgent(
                "agent-linchong", "林冲", AgentConstants.STATUS_BUSY, "[]");

        when(agentTaskMetaDao.countSearch(
                "juyiting", "jia_client", AgentConstants.TASK_STATUS_OPEN,
                "planning", "榜文"))
                .thenReturn(502L);
        when(agentTaskMetaDao.searchPage(
                "juyiting", "jia_client", AgentConstants.TASK_STATUS_OPEN,
                "planning", "榜文", 500L, 500))
                .thenReturn(List.of(first, second));
        when(agentTaskMetaDao.findSearchMembers(
                "juyiting", "jia_client", List.of("1", "2")))
                .thenReturn(List.of(memberOne, memberTwo));
        when(agentTaskMetaDao.findSearchRuntimes(
                List.of("agent-wuyong", "agent-linchong")))
                .thenReturn(List.of(wuYong, linChong));
        authenticateTaskScope("juyiting", "jia_client");

        AgentTaskSearchDTO request = new AgentTaskSearchDTO();
        request.setStatus(AgentConstants.TASK_STATUS_OPEN);
        request.setAbility("planning");
        request.setKeyword(" 榜文 ");
        request.setPageNum(2);
        request.setPageSize(9999);

        PageInfo<AgentTaskDTO> result = agentService.searchTasks(request);

        assertEquals(502L, result.getTotal());
        assertEquals(2, result.getPageNum());
        assertEquals(500, result.getPageSize());
        assertEquals(List.of("1", "2"),
                result.getList().stream().map(AgentTaskDTO::getId).toList());
        assertEquals("夜探祝家庄榜文", result.getList().getFirst().getTitle());
        assertEquals("吴用", result.getList().getFirst().getAssignedAgentName());
        verify(agentTaskMetaDao).countSearch(
                "juyiting", "jia_client", AgentConstants.TASK_STATUS_OPEN,
                "planning", "榜文");
        verify(agentTaskMetaDao).searchPage(
                "juyiting", "jia_client", AgentConstants.TASK_STATUS_OPEN,
                "planning", "榜文", 500L, 500);
        verify(agentTaskMetaDao).findSearchMembers(
                "juyiting", "jia_client", List.of("1", "2"));
        verify(agentTaskMetaDao).findSearchRuntimes(
                List.of("agent-wuyong", "agent-linchong"));
        verify(agentTaskMemberDao, never()).listByTask(any(), any(), any());
        verify(agentRuntimeDao, never()).findByAgentId(any());
        verify(taskService, never()).get(anyLong());
        verify(taskServiceProvider, never()).getIfAvailable();
    }

    @Test
    void expiredHostingRejectsAssignmentInLockedPreparationBeforeMutationOrDispatch() {
        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setAgentId("agent-001"); agent.setStatus(AgentConstants.STATUS_ONLINE); markOwned(agent);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(agent);
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L); meta.setTaskId("task-001"); meta.setTenantId("juyiting"); meta.setClientId("jia_client");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN); meta.setTaskVersion(0L);
        when(agentTaskMetaDao.findByTaskId("juyiting", "jia_client", "task-001")).thenReturn(meta);
        var gate = org.mockito.Mockito.mock(cn.jia.agent.service.AgentHostingWorkAdmission.class);
        agentService.setHostingWorkAdmission(gate);
        org.mockito.Mockito.doThrow(new cn.jia.agent.hosting.HostingRentApplicationException(409,
                "HOSTING_RENT_RENEWAL_REQUIRED")).when(gate).requireNewWork("juyiting", "jia_client", "agent-001");
        AgentTaskAssignDTO request = new AgentTaskAssignDTO(); request.setAgentId("agent-001");
        assertThrows(cn.jia.agent.hosting.HostingRentApplicationException.class,
                () -> agentService.assignTask("task-001", request));
        verify(agentRuntimeDao).findByAgentIdForUpdate("agent-001");
        verify(agentTaskMetaDao, never()).insert(any()); verify(agentTaskMetaDao, never()).updateById(any());
        verify(agentRuntimeDao, never()).updateById(any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher, taskEventWriter);
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
    void mapUsesThreeFixedBatchReadsForHundredAgents() {
        List<AgentRuntimeEntity> runtimes = new java.util.ArrayList<>();
        List<AgentPersonaEntity> personas = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String agentId = "agent-map-" + index;
            String personaCode = "persona-map-" + index;
            AgentRuntimeEntity runtime = runtimeAgent(
                    agentId, "Map Agent " + index, AgentConstants.STATUS_ONLINE, "[]");
            runtime.setPersonaCode(personaCode);
            runtime.setPersonaName("Map Persona " + index);
            runtime.setClientId("jia_client");
            runtime.setOwnerJiacn(index % 2 == 0 ? "juyiting" : "tenant-b");
            runtime.setBindingId((long) index + 1);
            runtimes.add(runtime);
            personas.add(persona(personaCode, "Map Persona " + index, "Map Title " + index));
        }
        AgentPersonaEntity songjiang = persona(
                AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE, "宋江", "及时雨");
        songjiang.setSystemAgent(true);
        personas.add(songjiang);
        when(agentRuntimeDao.findMapVisible("jia_client")).thenReturn(runtimes);
        when(agentPersonaDao.findRuntimeProjection()).thenReturn(personas);
        when(agentTaskMetaDao.findStatsByAgents(any())).thenAnswer(invocation ->
                invocation.<List<AgentTaskStatsScope>>getArgument(0).stream()
                        .map(scope -> taskStats(scope, 2, 1, 1, 1, 12))
                        .toList());

        List<AgentRuntimeDTO> result = agentService.listMapAgents();

        assertEquals(101, result.size());
        assertEquals(List.of("agent-map-0", "agent-map-1", "agent-map-2"), result.stream()
                .limit(3).map(AgentRuntimeDTO::getAgentId).toList());
        assertEquals(1, result.getFirst().getStats().getCompletedTaskCount());
        assertEquals(12L, result.getFirst().getStats().getAverageDurationSeconds());
        assertEquals(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, result.getLast().getAgentId());
        verify(agentRuntimeDao, times(1)).findMapVisible("jia_client");
        verify(agentPersonaDao, times(1)).findRuntimeProjection();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTaskStatsScope>> scopes = ArgumentCaptor.forClass(List.class);
        verify(agentTaskMetaDao, times(1)).findStatsByAgents(scopes.capture());
        assertEquals(100, scopes.getValue().size());
        assertEquals("juyiting", scopes.getValue().get(0).getTenantId());
        assertEquals("tenant-b", scopes.getValue().get(1).getTenantId());
        verify(agentPersonaDao, never()).findByCode(any());
        verify(agentPersonaDao, never()).findByName(any());
        verify(agentTaskMetaDao, never()).findByAgentId(any(), any(), any(), anyInt());
    }

    @Test
    void mapKeepsSameAgentIdTaskStatsSeparatedByTenant() {
        AgentRuntimeEntity tenantA = runtimeAgent(
                "shared-agent", "Tenant A Agent", AgentConstants.STATUS_ONLINE, "[]");
        tenantA.setClientId("client-a");
        tenantA.setOwnerJiacn("tenant-a");
        tenantA.setBindingId(1L);
        AgentRuntimeEntity tenantB = runtimeAgent(
                "shared-agent", "Tenant B Agent", AgentConstants.STATUS_BUSY, "[]");
        tenantB.setClientId("client-a");
        tenantB.setOwnerJiacn("tenant-b");
        tenantB.setBindingId(2L);
        EsContext context = new EsContext();
        context.setClientId("client-a");
        context.setJiacn("tenant-a");
        EsContextHolder.setContext(context);
        when(agentRuntimeDao.findMapVisible("client-a")).thenReturn(List.of(tenantA, tenantB));
        when(agentPersonaDao.findRuntimeProjection()).thenReturn(List.of());
        when(agentTaskMetaDao.findStatsByAgents(any())).thenReturn(List.of(
                taskStats(new AgentTaskStatsScope("tenant-a", "client-a", "shared-agent"),
                        3, 2, 1, 2, 20),
                taskStats(new AgentTaskStatsScope("tenant-b", "client-a", "shared-agent"),
                        5, 4, 1, 4, 80)));

        List<AgentRuntimeDTO> result = agentService.listMapAgents();

        assertEquals(3, result.size());
        assertEquals("tenant-a", result.get(0).getOwnerJiacn());
        assertTrue(result.get(0).getBoundToMe());
        assertTrue(result.get(0).getCanOperate());
        assertEquals(2, result.get(0).getStats().getCompletedTaskCount());
        assertEquals(10L, result.get(0).getStats().getAverageDurationSeconds());
        assertEquals("tenant-b", result.get(1).getOwnerJiacn());
        assertFalse(result.get(1).getBoundToMe());
        assertFalse(result.get(1).getCanOperate());
        assertEquals(4, result.get(1).getStats().getCompletedTaskCount());
        assertEquals(20L, result.get(1).getStats().getAverageDurationSeconds());
    }

    @Test
    void rosterUsesThreeFixedBatchReadsForHundredAgentsAndPreservesOrder() {
        List<AgentRuntimeEntity> runtimes = new java.util.ArrayList<>();
        List<AgentPersonaEntity> personas = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String agentId = String.format("agent-roster-%03d", index);
            String personaCode = String.format("persona-roster-%03d", index);
            AgentRuntimeEntity runtime = runtimeAgent(
                    agentId, "Roster Agent " + index, AgentConstants.STATUS_OFFLINE, "[]");
            runtime.setPersonaCode(personaCode);
            runtime.setPersonaName("Roster Persona " + index);
            runtime.setClientId("jia_client");
            runtime.setOwnerJiacn("juyiting");
            runtime.setBindingId((long) index + 1);
            runtimes.add(runtime);
            personas.add(persona(personaCode,
                    "Roster Persona " + index, "Roster Title " + index));
        }
        when(agentRuntimeDao.findRosterByOwner(
                "jia_client", "juyiting", AgentConstants.STATUS_OFFLINE, "planning"))
                .thenReturn(runtimes);
        when(agentPersonaDao.findRuntimeProjection()).thenReturn(personas);
        when(agentTaskMetaDao.findStatsByAgents(any())).thenAnswer(invocation ->
                invocation.<List<AgentTaskStatsScope>>getArgument(0).stream()
                        .map(scope -> taskStats(scope, 3, 2, 1, 2, 30))
                        .toList());

        try {
            PageInfo<AgentRuntimeDTO> result = agentService.listRoster(
                    AgentConstants.STATUS_OFFLINE, "planning", 1, 100);

            assertEquals(100, result.getList().size());
            assertEquals("agent-roster-000", result.getList().getFirst().getAgentId());
            assertEquals("agent-roster-099", result.getList().getLast().getAgentId());
            assertEquals(2, result.getList().getFirst().getStats().getCompletedTaskCount());
            assertEquals(15L, result.getList().getFirst().getStats().getAverageDurationSeconds());
            verify(agentRuntimeDao, times(1)).findRosterByOwner(
                    "jia_client", "juyiting", AgentConstants.STATUS_OFFLINE, "planning");
            verify(agentPersonaDao, times(1)).findRuntimeProjection();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AgentTaskStatsScope>> scopes = ArgumentCaptor.forClass(List.class);
            verify(agentTaskMetaDao, times(1)).findStatsByAgents(scopes.capture());
            assertEquals(100, scopes.getValue().size());
            assertTrue(scopes.getValue().stream().allMatch(scope ->
                    "juyiting".equals(scope.getTenantId())
                            && "jia_client".equals(scope.getClientId())));
            verify(agentPersonaDao, never()).findByCode(any());
            verify(agentPersonaDao, never()).findByName(any());
            verify(agentTaskMetaDao, never()).findByAgentId(any(), any(), any(), anyInt());
        } finally {
            PageHelper.clearPage();
        }
    }

    @Test
    void rosterRejectsCaseVariantClientRowsBeforeAnyEnrichmentQuery() {
        AgentRuntimeEntity leaked = runtimeAgent(
                "agent-case-variant", "Case Variant", AgentConstants.STATUS_ONLINE, "[]");
        leaked.setClientId("JIA_CLIENT");
        leaked.setOwnerJiacn("juyiting");
        leaked.setBindingId(1L);
        when(agentRuntimeDao.findRosterByOwner(
                "jia_client", "juyiting", null, null)).thenReturn(List.of(leaked));

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.listRoster(null, null, 1, 50));

        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, failure.getCode());
        verify(agentPersonaDao, never()).findRuntimeProjection();
        verify(agentTaskMetaDao, never()).findStatsByAgents(any());
    }

    @Test
    void rosterRejectsCaseVariantOwnerRowsBeforeAnyEnrichmentQuery() {
        AgentRuntimeEntity leaked = runtimeAgent(
                "agent-owner-variant", "Owner Variant", AgentConstants.STATUS_ONLINE, "[]");
        leaked.setClientId("jia_client");
        leaked.setOwnerJiacn("JUYITING");
        leaked.setBindingId(1L);
        when(agentRuntimeDao.findRosterByOwner(
                "jia_client", "juyiting", null, null)).thenReturn(List.of(leaked));

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> agentService.listRoster(null, null, 1, 50));

        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, failure.getCode());
        verify(agentPersonaDao, never()).findRuntimeProjection();
        verify(agentTaskMetaDao, never()).findStatsByAgents(any());
    }

    @Test
    void mapRejectsCaseVariantClientRowsBeforeAnyEnrichmentQuery() {
        AgentRuntimeEntity leaked = runtimeAgent(
                "agent-map-client-variant", "Map Client Variant",
                AgentConstants.STATUS_ONLINE, "[]");
        leaked.setClientId("CLIENT-A");
        leaked.setOwnerJiacn("tenant-a");
        leaked.setBindingId(1L);
        EsContext context = new EsContext();
        context.setClientId("client-a");
        context.setJiacn("tenant-a");
        EsContextHolder.setContext(context);
        when(agentRuntimeDao.findMapVisible("client-a")).thenReturn(List.of(leaked));

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class, agentService::listMapAgents);

        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, failure.getCode());
        verify(agentPersonaDao, never()).findRuntimeProjection();
        verify(agentTaskMetaDao, never()).findStatsByAgents(any());
    }

    @Test
    void mapRejectsDuplicateTaskStatsForSameByteExactScope() {
        AgentRuntimeEntity runtime = runtimeAgent(
                "agent-owned", "Owned", AgentConstants.STATUS_ONLINE, "[]");
        runtime.setClientId("client-a");
        runtime.setOwnerJiacn("tenant-a");
        runtime.setBindingId(1L);
        EsContext context = new EsContext();
        context.setClientId("client-a");
        context.setJiacn("tenant-a");
        EsContextHolder.setContext(context);
        when(agentRuntimeDao.findMapVisible("client-a")).thenReturn(List.of(runtime));
        when(agentPersonaDao.findRuntimeProjection()).thenReturn(List.of());
        AgentTaskStatsScope scope = new AgentTaskStatsScope(
                "tenant-a", "client-a", "agent-owned");
        when(agentTaskMetaDao.findStatsByAgents(any())).thenReturn(List.of(
                taskStats(scope, 1, 1, 0, 1, 10),
                taskStats(scope, 1, 1, 0, 1, 10)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, agentService::listMapAgents);

        assertTrue(failure.getMessage().contains("duplicate byte-exact scope"));
    }

    @Test
    void mapRejectsTaskStatsFromDifferentTenantOrClient() {
        AgentRuntimeEntity runtime = runtimeAgent(
                "agent-owned", "Owned", AgentConstants.STATUS_ONLINE, "[]");
        runtime.setClientId("client-a");
        runtime.setOwnerJiacn("tenant-a");
        runtime.setBindingId(1L);
        EsContext context = new EsContext();
        context.setClientId("client-a");
        context.setJiacn("tenant-a");
        EsContextHolder.setContext(context);
        when(agentRuntimeDao.findMapVisible("client-a")).thenReturn(List.of(runtime));
        when(agentPersonaDao.findRuntimeProjection()).thenReturn(List.of());
        AgentTaskStatsScope foreignScope = new AgentTaskStatsScope(
                "tenant-a", "client-b", "agent-owned");
        when(agentTaskMetaDao.findStatsByAgents(any())).thenReturn(List.of(
                taskStats(foreignScope, 1, 1, 0, 0, 0)));

        AgentServiceImpl.AgentBizException failure = assertThrows(
                AgentServiceImpl.AgentBizException.class, agentService::listMapAgents);

        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, failure.getCode());
    }

    @Test
    void listRosterReturnsOnlyCurrentOwnedBindingsAsOperable() {
        AgentRuntimeEntity agent = ownedAgent(
                "agent-001", "Wu Yong", AgentConstants.STATUS_OFFLINE, "[\"planning\"]");
        when(agentRuntimeDao.findRosterByOwner(
                "jia_client", "juyiting", AgentConstants.STATUS_OFFLINE, "planning"))
                .thenReturn(List.of(agent));

        PageInfo<AgentRuntimeDTO> page = agentService.listRoster(
                AgentConstants.STATUS_OFFLINE, "planning", 1, 20);

        assertEquals(1, page.getTotal());
        assertEquals(1, page.getList().size());
        AgentRuntimeDTO result = page.getList().getFirst();
        assertEquals("agent-001", result.getAgentId());
        assertEquals(AgentConstants.STATUS_OFFLINE, result.getStatus());
        assertTrue(result.getBound());
        assertTrue(result.getBoundToMe());
        assertTrue(result.getCanOperate());
        verify(agentIdentityService, never()).requireActiveIdentityForBinding(
                anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(agentIdentityService, never()).requireActiveBinding(any(), any());
        verify(eventPublisherProvider, never()).getIfAvailable();
    }

    @Test
    void listRosterPreservesPageHelperMetadataDuringDtoConversion() {
        AgentRuntimeEntity first = ownedAgent(
                "agent-003", "Lin Chong", AgentConstants.STATUS_OFFLINE, "[\"combat\"]");
        AgentRuntimeEntity second = ownedAgent(
                "agent-004", "Lu Zhishen", AgentConstants.STATUS_OFFLINE, "[\"combat\"]");
        Page<AgentRuntimeEntity> persistedPage = new Page<>(2, 2);
        persistedPage.setTotal(5L);
        persistedPage.add(first);
        persistedPage.add(second);
        when(agentRuntimeDao.findRosterByOwner(
                "jia_client", "juyiting", AgentConstants.STATUS_OFFLINE, "combat"))
                .thenReturn(persistedPage);

        try {
            PageInfo<AgentRuntimeDTO> page = agentService.listRoster(
                    AgentConstants.STATUS_OFFLINE, "combat", 2, 2);

            assertEquals(5L, page.getTotal());
            assertEquals(2, page.getPageNum());
            assertEquals(2, page.getPageSize());
            assertEquals(3, page.getPages());
            assertEquals(List.of("agent-003", "agent-004"), page.getList().stream()
                    .map(AgentRuntimeDTO::getAgentId)
                    .toList());
        } finally {
            PageHelper.clearPage();
        }
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
                eq("juyiting"), eq("jia_client"), eq("task-001"), eq(List.of("agent-wuyong")), eq(false),
                any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class));
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
                eq("juyiting"), eq("jia_client"), eq("task-001"), eq(List.of("agent-wuyong")), eq(false),
                any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class));
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
                eq("juyiting"), eq("jia_client"), eq("task-001"), eq(List.of("agent-wuyong")),
                eq(false), any(AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator.class)))
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

    private static Stream<Arguments> invalidTaskScopeAuthentications() {
        Stream.Builder<Arguments> cases = Stream.builder();
        cases.add(Arguments.of("no authentication", (Authentication) null));
        cases.add(Arguments.of("authenticated non-JWT",
                new UsernamePasswordAuthenticationToken("user", "credential", List.of())));
        cases.add(Arguments.of("missing jiacn", taskScopeAuthentication(null, "client-a")));
        cases.add(Arguments.of("missing client_id", taskScopeAuthentication("tenant-a", null)));
        cases.add(Arguments.of("non-String jiacn", taskScopeAuthentication(7L, "client-a")));
        cases.add(Arguments.of("non-String client_id",
                taskScopeAuthentication("tenant-a", Boolean.TRUE)));
        cases.add(Arguments.of("jiacn contains U+0000 control",
                taskScopeAuthentication("tenant" + (char) 0x0000 + "-a", "client-a")));
        cases.add(Arguments.of("client_id contains U+000A control",
                taskScopeAuthentication("tenant-a", "client" + (char) 0x000A + "-a")));
        for (int codeUnit : new int[]{0xD800, 0xDC00}) {
            String surrogate = String.valueOf((char) codeUnit);
            String label = String.format("U+%04X", codeUnit);
            cases.add(Arguments.of("jiacn contains unpaired " + label,
                    taskScopeAuthentication("tenant-" + surrogate, "client-a")));
            cases.add(Arguments.of("client_id contains unpaired " + label,
                    taskScopeAuthentication("tenant-a", "client-" + surrogate)));
        }
        for (int codePoint : new int[]{0x0020, 0x00A0, 0x2007, 0x202F}) {
            String padding = new String(Character.toChars(codePoint));
            String label = String.format("U+%04X", codePoint);
            cases.add(Arguments.of("jiacn leading " + label,
                    taskScopeAuthentication(padding + "tenant-a", "client-a")));
            cases.add(Arguments.of("jiacn trailing " + label,
                    taskScopeAuthentication("tenant-a" + padding, "client-a")));
            cases.add(Arguments.of("client_id leading " + label,
                    taskScopeAuthentication("tenant-a", padding + "client-a")));
            cases.add(Arguments.of("client_id trailing " + label,
                    taskScopeAuthentication("tenant-a", "client-a" + padding)));
        }
        cases.add(Arguments.of("legacy jiacn zero",
                taskScopeAuthentication("0", "client-a")));
        cases.add(Arguments.of("legacy client_id zero",
                taskScopeAuthentication("tenant-a", "0")));
        return cases.build();
    }

    private static Stream<Arguments> contaminatedTaskRows() {
        return Stream.of(
                Arguments.of("foreign tenant", "tenant-b", "client-a"),
                Arguments.of("foreign client", "tenant-a", "client-b"),
                Arguments.of("tenant case drift", "Tenant-A", "client-a"),
                Arguments.of("client case drift", "tenant-a", "Client-A"),
                Arguments.of("leading padded tenant", " tenant-a", "client-a"),
                Arguments.of("trailing padded tenant", "tenant-a ", "client-a"),
                Arguments.of("leading padded client", "tenant-a", " client-a"),
                Arguments.of("trailing padded client", "tenant-a", "client-a "));
    }

    private static JwtAuthenticationToken taskScopeAuthentication(
            Object tenantClaim, Object clientClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("task-search-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (tenantClaim != null) {
            builder.claim("jiacn", tenantClaim);
        }
        if (clientClaim != null) {
            builder.claim("client_id", clientClaim);
        }
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    private static AgentTaskSearchRow searchRow(
            String taskId, String tenantId, String clientId, String status) {
        AgentTaskSearchRow task = new AgentTaskSearchRow();
        task.setTaskId(taskId);
        task.setTenantId(tenantId);
        task.setClientId(clientId);
        task.setRewardStatus(status);
        task.setFundingPresent(0);
        return task;
    }

    private static AgentTaskStatusCountRow statusCount(
            String tenantId, String clientId, String status, long count) {
        AgentTaskStatusCountRow row = new AgentTaskStatusCountRow();
        row.setTenantId(tenantId);
        row.setClientId(clientId);
        row.setStatus(status);
        row.setTaskCount(count);
        return row;
    }

    private static AgentTaskMetaEntity scopedTaskRow(
            String taskId, String tenantId, String clientId) {
        AgentTaskMetaEntity task = new AgentTaskMetaEntity();
        task.setTaskId(taskId);
        task.setTenantId(tenantId);
        task.setClientId(clientId);
        task.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        return task;
    }

    private void authenticateTaskScope(String tenantId, String clientId) {
        SecurityContextHolder.getContext().setAuthentication(
                taskScopeAuthentication(tenantId, clientId));
    }

    private AgentTaskMemberEntity taskMember(
            String tenantId, String clientId, String taskId, String agentId, String status) {
        AgentTaskMemberEntity member = new AgentTaskMemberEntity();
        member.setTenantId(tenantId);
        member.setClientId(clientId);
        member.setTaskId(taskId);
        member.setAgentId(agentId);
        member.setMemberStatus(status);
        member.setMemberRole("worker");
        member.setAssignmentSource("manual");
        return member;
    }

    private AgentServiceImpl newAgentService(
            AgentScopePublicationCoordinator coordinator, AgentSceneFeatureFlags featureFlags) {
        return new AgentServiceImpl(agentRuntimeDao, agentIdentityService, agentPersonaDao,
                agentPersonaBindingDao, agentTaskMetaDao, agentTaskMemberDao,
                legacyTaskCompatibilityService, agentTaskNoteDao, dialogueTemplateDao,
                eventPublisherProvider, taskServiceProvider, apiKeyServiceProvider,
                sceneServiceProvider, coordinator, featureFlags, mutationTransaction, taskEventWriter);
    }

    private AgentScopePublicationCoordinator coordinatedPublication(AtomicBoolean insideScope) {
        AgentScopePublicationCoordinator coordinator =
                org.mockito.Mockito.mock(AgentScopePublicationCoordinator.class);
        doAnswer(invocation -> {
            assertFalse(insideScope.get(), "scope publication must not reenter the same test lock");
            insideScope.set(true);
            try {
                invocation.<Runnable>getArgument(2).run();
            } finally {
                insideScope.set(false);
            }
            return null;
        }).when(coordinator).execute(
                eq("jia_client"), eq("juyiting"), any(Runnable.class));
        return coordinator;
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

    private AgentTaskStatsRow taskStats(AgentTaskStatsScope scope, long taskCount,
            long completedCount, long failedCount, long durationCount, long durationSeconds) {
        AgentTaskStatsRow row = new AgentTaskStatsRow();
        row.setTenantId(scope.getTenantId());
        row.setClientId(scope.getClientId());
        row.setAgentId(scope.getAgentId());
        row.setTaskCount(taskCount);
        row.setCompletedTaskCount(completedCount);
        row.setFailedTaskCount(failedCount);
        row.setCompletedDurationCount(durationCount);
        row.setCompletedDurationSeconds(durationSeconds);
        return row;
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

    private AgentPersonaCatalogBindingRow catalogOverlay(
            AgentPersonaBindingEntity binding, AgentIdentityRegistryEntity identity,
            AgentRuntimeEntity runtime) {
        AgentPersonaCatalogBindingRow row = new AgentPersonaCatalogBindingRow();
        row.setBindingId(binding.getId());
        row.setBindingTenantId(binding.getTenantId());
        row.setBindingClientId(binding.getClientId());
        row.setBindingOwnerJiacn(binding.getJiacn());
        row.setPersonaCode(binding.getPersonaCode());
        row.setBindingAgentId(binding.getAgentId());
        row.setBindingStatus(binding.getStatus());
        row.setIdentityId(identity.getId());
        row.setIdentityBindingId(identity.getBindingId());
        row.setIdentityTenantId(identity.getTenantId());
        row.setIdentityClientId(identity.getClientId());
        row.setIdentityOwnerJiacn(identity.getOwnerJiacn());
        row.setCanonicalAgentId(identity.getCanonicalAgentId());
        row.setCanonicalType(identity.getCanonicalType());
        row.setLifecycleStatus(identity.getLifecycleStatus());
        row.setAgentReferenceValid(true);
        if (runtime != null) {
            row.setRuntimeId(runtime.getId() == null ? 20L : runtime.getId());
            row.setRuntimeTenantId(runtime.getTenantId());
            row.setRuntimeClientId(runtime.getClientId());
            row.setRuntimeOwnerJiacn(runtime.getOwnerJiacn());
            row.setRuntimeBindingId(runtime.getBindingId());
            row.setRuntimeAgentId(runtime.getAgentId());
            row.setRuntimeAbilities(runtime.getAbilities());
            row.setRuntimeStatus(runtime.getStatus());
        }
        return row;
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
