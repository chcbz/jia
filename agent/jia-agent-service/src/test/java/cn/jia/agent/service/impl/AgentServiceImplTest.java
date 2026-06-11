package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentStatsDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.task.service.TaskService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    AgentTaskMetaDao agentTaskMetaDao;
    @Mock
    DialogueTemplateDao dialogueTemplateDao;
    @Mock
    ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    @Mock
    ObjectProvider<TaskService> taskServiceProvider;
    @Mock
    AgentEventPublisher eventPublisher;
    AgentServiceImpl agentService;

    @BeforeEach
    void setUpAgentService() {
        agentService = new AgentServiceImpl(agentRuntimeDao, agentPersonaDao, agentTaskMetaDao, dialogueTemplateDao,
                eventPublisherProvider, taskServiceProvider);
    }

    @Test
    void registerCreatesOnlineAgentAndPublishesStatus() {
        when(agentRuntimeDao.findByAgentId("agent-001")).thenReturn(null);
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId("agent-001");
        request.setName("Wu Yong");
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
        assertEquals(AgentConstants.STATUS_ONLINE, saved.getStatus());
        assertNotNull(saved.getLastSeenAt());
        assertNotNull(saved.getTokenHash());

        ArgumentCaptor<AgentRuntimeDTO> eventCaptor = ArgumentCaptor.forClass(AgentRuntimeDTO.class);
        verify(eventPublisher).publishAgentStatus(eventCaptor.capture());
        assertEquals("agent-001", eventCaptor.getValue().getAgentId());
        assertEquals(AgentConstants.STATUS_ONLINE, eventCaptor.getValue().getStatus());
    }

    @Test
    void assignTaskRejectsOfflineAgent() {
        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setAgentId("agent-001");
        agent.setStatus(AgentConstants.STATUS_OFFLINE);
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
        when(agentRuntimeDao.findByStatusAndAbility(AgentConstants.STATUS_OFFLINE, "planning"))
                .thenReturn(List.of(agent));

        List<AgentRuntimeDTO> result = agentService.listRoster(AgentConstants.STATUS_OFFLINE, "planning", 1, 20)
                .getList();

        assertEquals(1, result.size());
        assertEquals("agent-001", result.getFirst().getAgentId());
        assertEquals(AgentConstants.STATUS_OFFLINE, result.getFirst().getStatus());
        verify(agentRuntimeDao).findByStatusAndAbility(AgentConstants.STATUS_OFFLINE, "planning");
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
}
