package cn.jia.agent.security;

import cn.jia.agent.api.PersonalWorkspaceRuntimeFileController;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The native terminal receipt stays on the runtime-authenticated lane and forwards its exact scope. */
class PersonalWorkspaceRuntimeFileControllerTest {
    @Test
    void runtimeFailureReceiptUsesExactRuntimeScopeAndNoStoreResponse() {
        PersonalWorkspaceExecutionService service = mock(PersonalWorkspaceExecutionService.class);
        PersonalWorkspaceRuntimeFileController controller = new PersonalWorkspaceRuntimeFileController(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterMap()).thenReturn(Map.of());
        AgentRuntimeAuthentication authentication = new AgentRuntimeAuthentication(
                new AgentRuntimeAuthentication.Scope("0", "client-a", "owner-a", "agent-a", "runtime-a"));
        PersonalWorkspaceExecutionService.ExecutionView failed = new PersonalWorkspaceExecutionService.ExecutionView(
                "pwe_1", "pwe_task_1", "pwe_run_1", null, "agent-a", "FAILED",
                "AGENT_DELIVERY_FAILED", "Agent 未能完成本次交付，请调整需求后重新创建执行。", 1L,
                "image/png", List.of(), null);
        when(service.fail(eq(new PersonalWorkspaceExecutionService.RuntimeScope("0", "client-a", "owner-a",
                "agent-a", "runtime-a")), eq("pwe_task_1"), eq("pwe_run_1"), eq("OUTPUT_MISSING")))
                .thenReturn(failed);

        var response = controller.failure("pwe_task_1", "pwe_run_1",
                new PersonalWorkspaceRuntimeFileController.FailureRequest("OUTPUT_MISSING"), request, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("FAILED", response.getBody().state());
        verify(service).fail(new PersonalWorkspaceExecutionService.RuntimeScope("0", "client-a", "owner-a",
                "agent-a", "runtime-a"), "pwe_task_1", "pwe_run_1", "OUTPUT_MISSING");
    }
}
