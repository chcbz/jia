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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
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

        when(agentRuntimeDao.findHeartbeatTimedOut(org.mockito.Mockito.anyLong()))
                .thenReturn(List.of(songjiang, normalAgent));
        when(eventPublisherProvider.getIfAvailable()).thenReturn(eventPublisher);

        monitor.markHeartbeatTimedOutAgentsOffline();

        assertEquals(AgentConstants.STATUS_ONLINE, songjiang.getStatus());
        assertEquals(AgentConstants.STATUS_OFFLINE, normalAgent.getStatus());
        verify(agentRuntimeDao, never()).updateById(songjiang);
        verify(agentRuntimeDao).updateById(normalAgent);
    }
}
