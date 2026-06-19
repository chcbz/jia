package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStatusMonitorTest extends BaseMockTest {
    @Mock
    AgentRuntimeDao agentRuntimeDao;
    @Mock
    ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    @Mock
    AgentEventPublisher eventPublisher;

    AgentStatusMonitor monitor;

    @BeforeEach
    void setUpMonitor() {
        monitor = new AgentStatusMonitor(agentRuntimeDao, eventPublisherProvider);
        ReflectionTestUtils.setField(monitor, "heartbeatTimeoutSeconds", 60L);
    }

    @Test
    void keepsBuiltinSongjiangOnlineWhenHeartbeatScanRuns() {
        AgentRuntimeEntity songjiang = new AgentRuntimeEntity();
        songjiang.setAgentId(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID);
        songjiang.setStatus(AgentConstants.STATUS_ONLINE);

        AgentRuntimeEntity normalAgent = new AgentRuntimeEntity();
        normalAgent.setAgentId("agent-wuyong");
        normalAgent.setStatus(AgentConstants.STATUS_ONLINE);

        when(agentRuntimeDao.findByStatusAndAbility(null, null)).thenReturn(List.of());
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong()))
                .thenReturn(List.of(songjiang, normalAgent));
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, songjiang.getStatus());
        assertEquals(AgentConstants.STATUS_OFFLINE, normalAgent.getStatus());
        verify(agentRuntimeDao, never()).updateById(songjiang);
        verify(agentRuntimeDao).updateById(normalAgent);
    }

    @Test
    void syncsAgentStatusesFromWebSocketConnections() {
        AgentRuntimeEntity idleConnected = new AgentRuntimeEntity();
        idleConnected.setAgentId("agent-idle");
        idleConnected.setStatus(AgentConstants.STATUS_OFFLINE);

        AgentRuntimeEntity busyConnected = new AgentRuntimeEntity();
        busyConnected.setAgentId("agent-busy");
        busyConnected.setStatus(AgentConstants.STATUS_ONLINE);
        busyConnected.setCurrentTaskId("task-001");
        busyConnected.setCurrentTaskTitle("处理悬赏");

        AgentRuntimeEntity disconnected = new AgentRuntimeEntity();
        disconnected.setAgentId("agent-stale");
        disconnected.setStatus(AgentConstants.STATUS_BUSY);
        disconnected.setCurrentTaskId("task-002");
        disconnected.setCurrentTaskTitle("旧任务");

        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);
        when(eventPublisher.connectedAgentIds()).thenReturn(Set.of("agent-idle", "agent-busy"));
        when(agentRuntimeDao.findByStatusAndAbility(null, null))
                .thenReturn(List.of(idleConnected, busyConnected, disconnected));
        when(agentRuntimeDao.findHeartbeatTimedOut(anyLong())).thenReturn(List.of());

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, idleConnected.getStatus());
        assertEquals(AgentConstants.STATUS_BUSY, busyConnected.getStatus());
        assertEquals(AgentConstants.STATUS_OFFLINE, disconnected.getStatus());
        assertEquals(null, disconnected.getCurrentTaskId());
        assertEquals(null, disconnected.getCurrentTaskTitle());
        verify(agentRuntimeDao, times(3)).updateById(org.mockito.Mockito.any(AgentRuntimeEntity.class));
    }
}
