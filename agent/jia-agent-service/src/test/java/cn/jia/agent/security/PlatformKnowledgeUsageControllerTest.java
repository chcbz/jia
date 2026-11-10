package cn.jia.agent.security;
import cn.jia.agent.api.PlatformKnowledgeUsageController;
import cn.jia.agent.service.usage.PlatformKnowledgeUsageRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformKnowledgeUsageControllerTest {
    static final String PATH = "/internal/agent/hosting-leases/lease/knowledge-usage";
    @Test void maintenanceRouteIsNativeOnlyAndCannotFallThroughToUserCredentialsOrAnotherOperation() {
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(new MockHttpServletRequest("POST", PATH)));
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST", PATH)));
        for (String method : new String[]{"GET", "PUT", "DELETE"})
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest(method, PATH)));
        for (String path : new String[]{PATH + "/execute", PATH.replace("/lease/", "/../"), PATH.replace("/lease/", "/%2f/")})
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST", path)));
    }
    @Test void originalNativeFenceProvidesScopeButReceiptNeverClaimsTaskSettlement() {
        var recorder = mock(PlatformKnowledgeUsageRecorder.class); var auth = mock(AgentRuntimeAuthenticationService.class);
        var principal = new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("tenant", "client", "owner", "agent", "instance"));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(), "installation", "host", 1, "digest", 1, 1));
        when(auth.withNativeFence(eq(principal), any())).thenAnswer(invocation -> ((Supplier<?>)invocation.getArgument(1)).get());
        when(recorder.record(any(), eq("lease"), eq("handover"), any())).thenReturn(new UsageCostService.Receipt("attempt", 1, TokenUsageCost.Status.PENDING_USAGE, null, false));
        var event = new PlatformKnowledgeUsageRecorder.Evidence("0", "extract", PlatformKnowledgeUsageRecorder.Phase.STARTED, "platform", "gpt-6-luna", "standard", null);
        var result = new PlatformKnowledgeUsageController(recorder, auth).record("lease", new JsonRequest<>(1, "request", new PlatformKnowledgeUsageController.Request("handover", event)), principal);
        assertEquals("1", result.getBody().getData().revision()); assertEquals("handover", result.getBody().getData().handoverId());
        assertNull(result.getBody().getData().costYuan());
        verify(recorder).record(new cn.jia.agent.service.AuthenticatedRuntimeOperation.Scope("tenant", "client", "owner", "agent"), "lease", "handover", event);
        verify(auth).withNativeFence(eq(principal), any());
    }
    @Test void stoppedEvidenceUsesPersistentNativeIdentityWithoutRequiringAnOnlineChannel() throws Exception {
        var auth = mock(AgentRuntimeAuthenticationService.class);
        var principal = new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("tenant", "client", "owner", "agent", "instance"));
        when(auth.authenticate(any(), eq(false))).thenReturn(principal);
        var request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("Authorization", "AgentRuntime rts1_" + "a".repeat(64));
        request.addHeader("X-Agent-Id", "agent"); request.addHeader("X-Agent-Installation-Id", "rti_" + "b".repeat(32));
        request.addHeader("X-Agent-Host-Id", "host"); request.addHeader("X-Agent-Runtime-Id", "instance");
        request.addHeader("X-Agent-Session-Generation", "1");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var reached = new java.util.concurrent.atomic.AtomicBoolean();
        new AgentRuntimeAuthenticationFilter(auth).doFilter(request, response, (req, res) -> reached.set(true));
        assertTrue(reached.get()); assertEquals(200, response.getStatus());
        verify(auth).authenticate(any(), eq(false));
        var unauthenticated = new org.springframework.mock.web.MockHttpServletResponse();
        reached.set(false);
        new AgentRuntimeAuthenticationFilter(auth).doFilter(new MockHttpServletRequest("POST", PATH), unauthenticated, (req, res) -> reached.set(true));
        assertEquals(401, unauthenticated.getStatus()); assertFalse(reached.get());
    }

}
