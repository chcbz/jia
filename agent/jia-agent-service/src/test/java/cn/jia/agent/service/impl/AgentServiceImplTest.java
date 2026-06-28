package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentStatsDTO;
import cn.jia.agent.entity.AgentTaskRecommendationDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplTest extends BaseMockTest {
    @Mock
    AgentRuntimeDao agentRuntimeDao;
    @Mock
    AgentPersonaDao agentPersonaDao;
    @Mock
    AgentPersonaBindingDao agentPersonaBindingDao;
    @Mock
    AgentTaskMetaDao agentTaskMetaDao;
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
    ApiKeyService apiKeyService;
    @Mock
    TaskService taskService;
    @Mock
    AgentEventPublisher eventPublisher;
    AgentServiceImpl agentService;

    @BeforeEach
    void setUpAgentService() {
        EsContextHolder.setContext(new EsContext());
        agentService = new AgentServiceImpl(agentRuntimeDao, agentPersonaDao, agentPersonaBindingDao, agentTaskMetaDao,
                agentTaskNoteDao, dialogueTemplateDao, eventPublisherProvider, taskServiceProvider, apiKeyServiceProvider);
    }

    @Test
    void registerCreatesOnlineAgentAndPublishesStatus() {
        AgentPersonaBindingEntity binding = binding("agent-001", "wuyong");
        AgentPersonaEntity persona = persona("wuyong", "吴用", "智多星");
        when(agentPersonaBindingDao.findActiveByClientJiacnAndAgentId("jia_client", "juyiting", "agent-001")).thenReturn(binding);
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
        verify(eventPublisher).publishAgentStatus(eventCaptor.capture());
        assertEquals("agent-001", eventCaptor.getValue().getAgentId());
        assertEquals(AgentConstants.STATUS_ONLINE, eventCaptor.getValue().getStatus());
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
        verify(eventPublisher).publishTaskEvent(eq("task_created"), any(AgentTaskDTO.class));
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
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);

        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentIds(List.of("agent-wuyong", "agent-linchong"));

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        assertEquals(AgentConstants.TASK_STATUS_ASSIGNED, result.getStatus());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), result.getAssignedAgentIds());
        assertEquals(2, result.getAssignees().size());
        assertEquals("Wu Yong", result.getAssignees().get(0).getAgentName());

        ArgumentCaptor<AgentTaskMetaEntity> metaCaptor = ArgumentCaptor.forClass(AgentTaskMetaEntity.class);
        verify(agentTaskMetaDao).updateById(metaCaptor.capture());
        assertEquals("[\"agent-wuyong\",\"agent-linchong\"]", metaCaptor.getValue().getAssignedAgentId());
        verify(eventPublisher).publishTaskEvent(eq("task_assigned"), any(AgentTaskDTO.class));
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
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);

        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentIds(List.of("agent-wuyong", "agent-linchong", "agent-wuyong"));

        AgentTaskDTO result = agentService.assignTask("task-001", request);

        ArgumentCaptor<AgentActionIntentDTO> intentCaptor = ArgumentCaptor.forClass(AgentActionIntentDTO.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishAgentAction(intentCaptor.capture());
        List<AgentActionIntentDTO> intents = intentCaptor.getAllValues();
        assertEquals(List.of("agent-wuyong", "agent-linchong"), intents.stream().map(AgentActionIntentDTO::getActorAgentId).toList());
        assertTrue(intents.stream().allMatch(intent -> "task_briefing".equals(intent.getActionType())));
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
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);

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
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setRequiredAbilities("[\"planning\"]");
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);

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
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setRequiredAbilities("[\"planning\",\"execution\"]");
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);

        AgentRuntimeEntity planner = ownedAgent("agent-wuyong", "吴用", AgentConstants.STATUS_ONLINE, "[\"planning\"]");
        AgentRuntimeEntity executor = ownedAgent("agent-linchong", "林冲", AgentConstants.STATUS_ONLINE, "[\"execution\"]");
        when(agentRuntimeDao.findRosterByOwner("jia_client", "juyiting", null, null)).thenReturn(List.of(planner, executor));
        when(agentRuntimeDao.findByAgentId("agent-wuyong")).thenReturn(planner);
        when(agentRuntimeDao.findByAgentId("agent-linchong")).thenReturn(executor);

        AgentTaskDTO result = agentService.autoAssignTask("task-001", new AgentTaskAssignDTO());

        assertEquals(AgentConstants.TASK_STATUS_ASSIGNED, result.getStatus());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), result.getAssignedAgentIds());
        ArgumentCaptor<AgentTaskMetaEntity> metaCaptor = ArgumentCaptor.forClass(AgentTaskMetaEntity.class);
        verify(agentTaskMetaDao).updateById(metaCaptor.capture());
        assertEquals("[\"agent-wuyong\",\"agent-linchong\"]", metaCaptor.getValue().getAssignedAgentId());
    }

    @Test
    void archiveTaskMarksTaskArchivedWithoutUpdatingAgentRuntime() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        meta.setAssignedAgentId("agent-wuyong");
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentTaskDTO result = agentService.archiveTask("task-001");

        assertEquals(AgentConstants.TASK_STATUS_ARCHIVED, result.getStatus());
        verify(agentTaskMetaDao).updateById(meta);
        verify(agentRuntimeDao, never()).updateById(any());
        verify(eventPublisher).publishTaskEvent(eq("task_archived"), any(AgentTaskDTO.class));
    }

    @Test
    void addTaskNoteStoresNoteAndListTaskNotesReturnsSavedNotes() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);

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
    void reportRunningUpdatesAgentBusyAndPublishesTaskEvent() {
        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setId(1L);
        meta.setTaskId("task-001");
        meta.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        meta.setAssignedAgentId("agent-001");
        when(agentTaskMetaDao.findByTaskId("task-001")).thenReturn(meta);

        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setId(2L);
        agent.setAgentId("agent-001");
        agent.setName("Wu Yong");
        agent.setStatus(AgentConstants.STATUS_ONLINE);
        markOwned(agent);
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(agent);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setStatus(AgentConstants.TASK_STATUS_RUNNING);
        request.setCurrentTaskTitle("Analyze order issue");

        AgentTaskDTO result = agentService.reportTask("task-001", request);

        assertEquals("task-001", result.getId());
        assertEquals(AgentConstants.TASK_STATUS_RUNNING, result.getStatus());
        assertEquals("agent-001", result.getAssignedAgentId());
        assertEquals("Wu Yong", result.getAssignedAgentName());

        ArgumentCaptor<AgentTaskMetaEntity> metaCaptor = ArgumentCaptor.forClass(AgentTaskMetaEntity.class);
        verify(agentTaskMetaDao).updateById(metaCaptor.capture());
        assertEquals(AgentConstants.TASK_STATUS_RUNNING, metaCaptor.getValue().getRewardStatus());
        assertNotNull(metaCaptor.getValue().getStartedAt());

        ArgumentCaptor<AgentRuntimeEntity> agentCaptor = ArgumentCaptor.forClass(AgentRuntimeEntity.class);
        verify(agentRuntimeDao).updateById(agentCaptor.capture());
        assertEquals(AgentConstants.STATUS_BUSY, agentCaptor.getValue().getStatus());
        assertEquals("task-001", agentCaptor.getValue().getCurrentTaskId());
        assertEquals("Analyze order issue", agentCaptor.getValue().getCurrentTaskTitle());

        verify(eventPublisher).publishAgentStatus(any(AgentRuntimeDTO.class));
        verify(eventPublisher).publishTaskEvent(eq("task_running"), any(AgentTaskDTO.class));
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
        completed.setRewardStatus(AgentConstants.TASK_STATUS_COMPLETED);
        completed.setStartedAt(1_000L);
        completed.setCompletedAt(61_000L);

        AgentTaskMetaEntity failed = new AgentTaskMetaEntity();
        failed.setTaskId("task-002");
        failed.setRewardStatus(AgentConstants.TASK_STATUS_FAILED);
        when(agentTaskMetaDao.findByAgentId("agent-001")).thenReturn(List.of(completed, failed));

        AgentRuntimeDTO result = agentService.getStats("agent-001");

        AgentStatsDTO stats = result.getStats();
        assertEquals(1, stats.getCompletedTaskCount());
        assertEquals(1, stats.getFailedTaskCount());
        assertEquals(60L, stats.getAverageDurationSeconds());
    }

    private void markOwned(AgentRuntimeEntity agent) {
        agent.setClientId("jia_client");
        agent.setOwnerJiacn("juyiting");
        when(agentPersonaBindingDao.findActiveByClientJiacnAndAgentId("jia_client", "juyiting", agent.getAgentId()))
                .thenReturn(binding(agent.getAgentId(), agent.getPersonaCode() == null ? agent.getAgentId() : agent.getPersonaCode()));
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
        binding.setJiacn("juyiting");
        binding.setAgentId(agentId);
        binding.setPersonaCode(personaCode);
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        return binding;
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
