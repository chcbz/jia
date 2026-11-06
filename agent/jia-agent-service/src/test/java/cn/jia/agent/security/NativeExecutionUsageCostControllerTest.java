package cn.jia.agent.security;

import cn.jia.agent.api.NativeExecutionUsageCostController;
import cn.jia.agent.service.usage.NativeExecutionUsageCostRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeExecutionUsageCostControllerTest {
    private static final String PATH="/internal/agent/tasks/task/runs/run/conversation/usage";
    @Test void nativeReceiptRouteDoesNotFallBackToBrowserCredentials() {
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(new MockHttpServletRequest("POST",PATH)));
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST",PATH)));
        for(String method : new String[]{"GET","PUT","DELETE"})
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest(method,PATH)));
    }
    @Test void responseAcknowledgesEvidenceWithTrustedScopeAndDecimalRevisionNotTaskCompletion() {
        var recorder=mock(NativeExecutionUsageCostRecorder.class);
        var auth=mock(AgentRuntimeAuthenticationService.class);
        var principal=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("0","client","executor","agent","instance"));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(),"installation","host",1,"digest",1,1));
        when(auth.withNativeFence(eq(principal),any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(recorder.record(any(),eq("task"),eq("run"),any())).thenAnswer(i -> {
            var scope=(cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeScope)i.getArgument(0);
            assertEquals("executor",scope.executorOwnerJiacn()); assertEquals("agent",scope.agentId());
            return new UsageCostService.Receipt("b".repeat(64),2,TokenUsageCost.Status.PENDING_USAGE,null,false);
        });
        var body=new NativeExecutionUsageCostController.Request("exec",NativeExecutionUsageCostRecorder.Phase.TERMINAL,"requested-only",new ObjectMapper().readTree("{\"status\":\"UNVERIFIED\"}"));
        var controller=new NativeExecutionUsageCostController(recorder,auth);
        var response=controller.record("task","run",new JsonRequest<>(1,"fixture",body),principal);
        assertEquals(200,response.getStatusCode().value());
        var receipt=response.getBody().getData();
        assertEquals("exec",receipt.executionId());assertEquals("2",receipt.revision());
        assertEquals("PENDING_USAGE",receipt.status());assertNull(receipt.costYuan());
        verify(auth).withNativeFence(eq(principal),any());
    }
    @Test void startedEvidenceCannotClaimUsageAndMissingPhaseCannotGuessSemantics() {
        var recorder=mock(NativeExecutionUsageCostRecorder.class);
        var controller=new NativeExecutionUsageCostController(recorder,mock(AgentRuntimeAuthenticationService.class));
        for (var phase : new NativeExecutionUsageCostRecorder.Phase[]{null, NativeExecutionUsageCostRecorder.Phase.STARTED}) {
            var body=new NativeExecutionUsageCostController.Request("exec",phase,"requested-only",new ObjectMapper().readTree("{}"));
            assertThrows(IllegalArgumentException.class, () -> controller.record("task","run",new JsonRequest<>(1,"fixture",body),null));
        }
        verifyNoInteractions(recorder);
    }

}
