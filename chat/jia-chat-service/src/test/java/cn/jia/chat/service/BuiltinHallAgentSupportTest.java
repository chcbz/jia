package cn.jia.chat.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.boot.ApplicationRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BuiltinHallAgentSupportTest extends BaseMockTest {
    @Mock
    AgentService agentService;

    @Test
    void builtInSupportIsNotAStartupRunnerThatRegistersTheSystemIdentity() {
        assertFalse(ApplicationRunner.class.isAssignableFrom(BuiltinHallAgentSupport.class));
    }

    @Test
    void missingRuntimeFallsBackToACompleteNonOperableBuiltInIdentity() {
        when(agentService.get(BuiltinHallAgentSupport.SONGJIANG_AGENT_ID))
                .thenThrow(new IllegalStateException("runtime unavailable"));
        BuiltinHallAgentSupport support = new BuiltinHallAgentSupport(agentService);

        AgentRuntimeDTO result = support.defaultAgent();

        assertEquals(BuiltinHallAgentSupport.SONGJIANG_AGENT_ID, result.getAgentId());
        assertEquals(AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE, result.getPersonaCode());
        assertEquals(Boolean.TRUE, result.getSystemAgent());
        assertEquals(Boolean.TRUE, result.getBound());
        assertEquals(Boolean.FALSE, result.getBoundToMe());
        assertEquals(Boolean.FALSE, result.getCanBind());
        assertEquals(Boolean.FALSE, result.getCanOperate());
        assertEquals(AgentConstants.STATUS_ONLINE, result.getStatus());
        assertEquals(BuiltinHallAgentSupport.SONGJIANG_ENDPOINT, result.getEndpoint());
        verify(agentService).get(BuiltinHallAgentSupport.SONGJIANG_AGENT_ID);
        verifyNoMoreInteractions(agentService);
    }

    @Test
    void existingRuntimeIsNormalizedAsBuiltInWithoutExternalMutationCalls() {
        AgentRuntimeDTO existing = new AgentRuntimeDTO();
        existing.setAgentId(BuiltinHallAgentSupport.SONGJIANG_AGENT_ID);
        existing.setName(BuiltinHallAgentSupport.SONGJIANG_NAME);
        existing.setStatus(AgentConstants.STATUS_OFFLINE);
        when(agentService.get(BuiltinHallAgentSupport.SONGJIANG_AGENT_ID)).thenReturn(existing);
        BuiltinHallAgentSupport support = new BuiltinHallAgentSupport(agentService);

        AgentRuntimeDTO result = support.defaultAgent();

        assertEquals(Boolean.TRUE, result.getSystemAgent());
        assertEquals(Boolean.FALSE, result.getCanOperate());
        assertEquals(AgentConstants.STATUS_ONLINE, result.getStatus());
        verify(agentService).get(BuiltinHallAgentSupport.SONGJIANG_AGENT_ID);
        verifyNoMoreInteractions(agentService);
    }
}
