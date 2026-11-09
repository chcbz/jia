package cn.jia.agent.security;

import cn.jia.core.filter.RequestIdFilter;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeErrorEnvelopeTest {
    void verifyFailure(int expected, RuntimeException failure, boolean forbidden) throws Exception {
        var service = mock(AgentRuntimeAuthenticationService.class);
        when(service.authenticate(any(), anyBoolean())).thenThrow(failure);
        var request = new MockHttpServletRequest("GET", "/internal/agent/chat/requests/req/turns/turn/tools");
        request.addHeader("Authorization", "AgentRuntime rts1_" + "a".repeat(64));
        request.addHeader("X-Agent-Id", "agt_" + "b".repeat(32));
        request.addHeader("X-Agent-Installation-Id", "rti_" + "c".repeat(32));
        request.addHeader("X-Agent-Host-Id", "host"); request.addHeader("X-Agent-Runtime-Id", "runtime");
        request.addHeader("X-Agent-Session-Generation", "1"); request.addHeader("X-Request-Id", "error-fixture");
        if (forbidden) request.addHeader("Origin", "https://example.invalid");
        var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
        new RequestIdFilter().doFilter(request, response, (req, res) ->
                new AgentRuntimeAuthenticationFilter(service).doFilter(req, res, (a, b) -> called.set(true)));
        assertFalse(called.get()); assertEquals(expected, response.getStatus());
        var json = JsonUtil.getMapper().readTree(response.getContentAsString());
        assertEquals(5, json.size()); assertEquals(1, json.path("protocolVersion").asInt());
        assertEquals("error-fixture", json.path("requestId").asText());
        assertEquals(json.path("requestId").asText(), response.getHeader("X-Request-Id"));
        assertEquals(expected == 503 ? "AGENT_RUNTIME_UNAVAILABLE" : "AGENT_RUNTIME_UNAUTHENTICATED", json.path("code").asText());
        assertFalse(json.path("msg").asText().isBlank()); assertTrue(json.path("data").isNull());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertFalse(response.getContentAsString().contains("SECRET"));
    }
    @Test void unauthorized() throws Exception { verifyFailure(401, new IllegalArgumentException("SECRET"), false); }
    @Test void forbidden() throws Exception { verifyFailure(403, new IllegalArgumentException("SECRET"), true); }
    @Test void unavailable() throws Exception { verifyFailure(503, new org.springframework.dao.DataAccessResourceFailureException("SECRET"), false); }
}
