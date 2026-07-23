package cn.jia.chat.api;

import cn.jia.chat.entity.AgentTaskThreadCreateDTO;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.AgentTaskThreadService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

class AgentTaskThreadControllerTest extends BaseMockTest {
    @Mock
    AgentTaskThreadService service;

    @AfterEach
    void clearContext() {
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void controllerTakesTenantAndClientOnlyFromAuthenticatedContext() {
        EsContext context = new EsContext();
        context.setJiacn("tenant-a");
        context.setClientId("client-a");
        EsContextHolder.setContext(context);
        AgentTaskThreadCreateDTO request = new AgentTaskThreadCreateDTO();
        request.setActorAgentId("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        request.setTitle("team");

        new AgentTaskThreadController(service)
                .getOrCreateTeamThread("task-1", request);

        verify(service).getOrCreateTeamThread(
                "tenant-a", "client-a", "task-1",
                "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "team");
    }

    @Test
    void memberDenialUsesSameNonLeakingNotFoundResponse() {
        AgentTaskThreadController controller = new AgentTaskThreadController(service);
        var response = controller.handleTaskThreadException(new AgentTaskThreadException(
                AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "internal member detail"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("TASK_THREAD_NOT_FOUND", response.getBody().getCode());
        assertEquals("Task thread is not available in the requested scope",
                response.getBody().getMsg());
    }
}
