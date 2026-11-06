package cn.jia.chat.service.usage;

import cn.jia.agent.security.*;
import cn.jia.chat.api.ChatUsageCostController;
import cn.jia.core.entity.JsonRequest;
import cn.jia.economy.usage.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatUsageCostControllerTest {
    @Test void routeIsNativeOnlyAndWriteOnlyWithoutCredentialFallback() {
        String path = "/internal/agent/chat/dispatches/dispatch/usage";
        for (String method : new String[]{"POST", "GET", "PUT", "DELETE"}) {
            var request = new MockHttpServletRequest(method, path);
            assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(request));
            assertEquals("POST".equals(method), AgentRuntimeAuthenticationFilter.allowed(request));
        }
    }
    @Test void acknowledgementUsesAuthenticatedScopeAndDoesNotInventCost() {
        var recorder = mock(ChatUsageCostRecorder.class);
        var auth = mock(AgentRuntimeAuthenticationService.class);
        var principal = mock(AgentRuntimeAuthentication.class);
        var scope = new AgentRuntimeAuthentication.Scope("0", "client", "executor", "agent", "instance");
        when(principal.isAuthenticated()).thenReturn(true);
        when(principal.getPrincipal()).thenReturn(scope);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(
                scope, "installation", "host", 1, "digest", 1, 1));
        when(auth.withNativeFence(eq(principal), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(recorder.record(eq(scope), eq("dispatch"), any())).thenReturn(
                new UsageCostService.Receipt("attempt", 2, TokenUsageCost.Status.PENDING_USAGE, null, false));
        var body = new ChatUsageCostController.Request(ChatUsageCostRecorder.Phase.TERMINAL,
                "requested-only", new ObjectMapper().readTree("{\"status\":\"UNVERIFIED\"}"));
        var response = new ChatUsageCostController(recorder, auth).record(
                "dispatch", new JsonRequest<>(1, "fixture", body), principal);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("dispatch", response.getBody().getData().dispatchId());
        assertEquals("2", response.getBody().getData().revision());
        assertEquals("PENDING_USAGE", response.getBody().getData().status());
        assertNull(response.getBody().getData().costYuan());
        verify(auth).withNativeFence(eq(principal), any());
        verify(recorder).record(eq(scope), eq("dispatch"), any());
    }
    @Test void protocolRejectsUnknownPhaseAndNonObjectUsageBeforeRecording() {
        var recorder = mock(ChatUsageCostRecorder.class);
        var auth = mock(AgentRuntimeAuthenticationService.class);
        var controller = new ChatUsageCostController(recorder, auth);
        var json = new ObjectMapper();
        for (var body : new ChatUsageCostController.Request[]{
                new ChatUsageCostController.Request(null, "model", null),
                new ChatUsageCostController.Request(ChatUsageCostRecorder.Phase.STARTED, "model", json.readTree("{}")),
                new ChatUsageCostController.Request(ChatUsageCostRecorder.Phase.TERMINAL, "model", json.readTree("[]"))}) {
            assertThrows(IllegalArgumentException.class, () -> controller.record("dispatch", new JsonRequest<>(1, "fixture", body), null));
        }
        verifyNoInteractions(recorder, auth);
    }
}
