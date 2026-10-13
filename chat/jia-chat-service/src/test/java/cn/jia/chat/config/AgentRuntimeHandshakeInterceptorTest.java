package cn.jia.chat.config;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Replaces obsolete API-key onboarding/refund authentication regressions. */
class AgentRuntimeHandshakeInterceptorTest {
    static final String AGENT = "agt_" + "a".repeat(32), INSTALLATION = "rti_" + "a".repeat(32);
    static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/ws/agent/channel");
        request.addHeader("Authorization", "AgentRuntime rts1_" + "b".repeat(64));
        request.addHeader("X-Agent-Id", AGENT); request.addHeader("X-Agent-Installation-Id", INSTALLATION);
        request.addHeader("X-Agent-Host-Id", "host"); request.addHeader("X-Agent-Runtime-Id", "boot");
        request.addHeader("X-Agent-Session-Generation", "3"); return request;
    }
    @Test void coldProvisionedSessionCarriesOnlyVerifiedNonSecretProof() {
        var auth = mock(AgentRuntimeAuthenticationService.class);
        var proof = new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0", "client", "owner", AGENT, "boot"),
                INSTALLATION, "host", 3, "d".repeat(64), 7, 2);
        when(auth.verify(any())).thenReturn(proof);
        var attrs = new HashMap<String, Object>();
        assertTrue(new AgentRuntimeHandshakeInterceptor(auth).beforeHandshake(new ServletServerHttpRequest(request()),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attrs));
        assertEquals(proof, attrs.get(AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE));
        assertEquals("0", attrs.get("tenantId")); assertEquals("owner", attrs.get("jiacn"));
        assertEquals(3L, attrs.get("runtimeSessionGeneration"));
        assertFalse(attrs.containsKey("sessionToken")); assertFalse(attrs.containsKey("managedApiKeyId"));
        assertFalse(attrs.toString().contains("rts1_"));
    }
    @Test void apiKeysQueryCredentialsOriginsAndDuplicateProofCannotFallBack() {
        for (int variant = 0; variant < 4; variant++) {
            var auth = mock(AgentRuntimeAuthenticationService.class); var request = request();
            if (variant == 0) request.addHeader("X-API-Key", "legacy-secret");
            if (variant == 1) request.setQueryString("api_key=legacy-secret");
            if (variant == 2) request.addHeader("Origin", "https://browser.invalid");
            if (variant == 3) request.addHeader("X-Agent-Session-Generation", "4");
            var response = mock(ServerHttpResponse.class);
            assertFalse(new AgentRuntimeHandshakeInterceptor(auth).beforeHandshake(new ServletServerHttpRequest(request), response,
                    mock(WebSocketHandler.class), new HashMap<>()));
            verify(response).setStatusCode(HttpStatus.UNAUTHORIZED); verifyNoInteractions(auth);
        }
    }
    @Test void revokedOrRefundedInstallationCannotActivateProvisionedAgent() {
        var auth = mock(AgentRuntimeAuthenticationService.class);
        when(auth.verify(any())).thenThrow(new IllegalArgumentException("private denial"));
        var response = mock(ServerHttpResponse.class); var attrs = new HashMap<String, Object>();
        assertFalse(new AgentRuntimeHandshakeInterceptor(auth).beforeHandshake(new ServletServerHttpRequest(request()), response,
                mock(WebSocketHandler.class), attrs));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED); assertTrue(attrs.isEmpty());
    }
    @Test void persistenceFailureIsNotReportedAsInvalidCredentialOrReady() {
        var auth = mock(AgentRuntimeAuthenticationService.class);
        when(auth.verify(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private failure"));
        var response = mock(ServerHttpResponse.class);
        assertFalse(new AgentRuntimeHandshakeInterceptor(auth).beforeHandshake(new ServletServerHttpRequest(request()), response,
                mock(WebSocketHandler.class), new HashMap<>()));
        verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
