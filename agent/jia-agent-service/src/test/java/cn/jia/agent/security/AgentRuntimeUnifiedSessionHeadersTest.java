package cn.jia.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

/** Syntax-only boundary test; never substitute it for persistent session authority/Flow verification. */
class AgentRuntimeUnifiedSessionHeadersTest {
    private static final String TOKEN = "rts1_" + "a".repeat(64);
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/ws/agent/channel");
        request.addHeader("Authorization", "AgentRuntime " + TOKEN);
        request.addHeader("X-Agent-Id", "agt_" + "b".repeat(32));
        request.addHeader("X-Agent-Installation-Id", "rti_" + "c".repeat(32));
        request.addHeader("X-Agent-Host-Id", "persistent-host");
        request.addHeader("X-Agent-Runtime-Id", "boot-instance");
        request.addHeader("X-Agent-Session-Generation", "7");
        return request;
    }
    @Test void exactProofHasSeparateHostInstanceAndServerGenerationWithoutDiagnosticSecrets() {
        var parsed = AgentRuntimeAuthenticationFilter.sessionHeaders(request());
        assertEquals(7, parsed.sessionGeneration()); assertEquals(TOKEN, parsed.token());
        assertNotEquals(parsed.hostId(), parsed.runtimeInstanceId());
        assertFalse(parsed.toString().contains(TOKEN)); assertTrue(parsed.toString().contains("REDACTED"));
    }
    @Test void duplicateSecurityHeadersAlwaysReject() {
        for (var header : new String[]{"Authorization", "X-Agent-Id", "X-Agent-Installation-Id",
                "X-Agent-Host-Id", "X-Agent-Runtime-Id", "X-Agent-Session-Generation"}) {
            var request = request(); request.addHeader(header, request.getHeader(header));
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        }
    }
    @Test void missingProofCannotBeTakenFromBodyOrCookie() {
        for (var header : new String[]{"X-Agent-Id", "X-Agent-Installation-Id", "X-Agent-Host-Id",
                "X-Agent-Runtime-Id", "X-Agent-Session-Generation"}) {
            var request = request(); request.removeHeader(header);
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        }
    }
    @Test void apiKeyUrlCredentialAndBrowserOriginCannotFallBackToInstallationBearer() {
        for (var header : new String[]{"X-API-Key", "Origin"}) {
            var request = request(); request.addHeader(header, "fixture-forbidden");
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        }
        for (var name : new String[]{"api_key", "apiKey", "token", "sessionToken", "access_token", "runtimeAuthorization"}) {
            var request = request(); request.addParameter(name, "fixture-forbidden");
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        }
    }
    @Test void malformedGenerationNeverSilentlyTruncatesOrWraps() {
        for (var generation : new String[]{"0", "-1", "07", "7.0", "7 ", "9223372036854775808"}) {
            var request = request(); request.removeHeader("X-Agent-Session-Generation"); request.addHeader("X-Agent-Session-Generation", generation);
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        }
    }
    @Test void legacyAndBearerTokensAreNotExecutionSessionCredentials() {
        for (var token : new String[]{"AgentRuntime " + "a".repeat(32), "Bearer rta1_fixture", "AgentRuntime rts1_" + "A".repeat(64)}) {
            var request = request(); request.removeHeader("Authorization"); request.addHeader("Authorization", token);
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        }
    }
}
