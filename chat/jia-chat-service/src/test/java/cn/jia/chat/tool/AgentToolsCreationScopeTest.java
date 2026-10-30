package cn.jia.chat.tool;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentToolsCreationScopeTest {
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }

    @Test
    void invocationTenantAndOwnerAreNeverReplacedByProductionDefaults() {
        var service = mock(AgentService.class);
        var context = new EsContext();
        context.setTenantId("1");
        context.setClientId("client-a");
        context.setJiacn("owner-a");
        EsContextHolder.setContext(context);
        new AgentTools(service).createAgentTask("title", "body", List.of("research"), 5);
        var request = ArgumentCaptor.forClass(AgentTaskCreateDTO.class);
        verify(service).createTask(eq(new AgentService.TaskOwnerScope("1", "client-a", "owner-a")),
                request.capture());
        assertEquals("title", request.getValue().getTitle());
        assertEquals(5, request.getValue().getReward());
    }
}
