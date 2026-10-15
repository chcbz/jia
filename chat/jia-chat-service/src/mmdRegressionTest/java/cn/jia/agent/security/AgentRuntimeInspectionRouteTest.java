package cn.jia.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeInspectionRouteTest {
    @Test
    void onlyExactGetInspectionContentRouteIsAllowed() {
        assertTrue(allowed("GET", "/internal/agent/chat/requests/request-1/turns/turn-1/inspection/inputs/source_1/content"));
        assertFalse(allowed("POST", "/internal/agent/chat/requests/request-1/turns/turn-1/inspection/inputs/source_1/content"));
        assertFalse(allowed("HEAD", "/internal/agent/chat/requests/request-1/turns/turn-1/inspection/inputs/source_1/content"));
        assertFalse(allowed("GET", "/internal/agent/chat/requests/request-1/turns/turn-1/inspection/inputs/source_1"));
        assertFalse(allowed("GET", "/internal/agent/chat/requests/request-1/turns/turn-1/inspection/inputs/source_1/content/extra"));
        assertFalse(allowed("GET", "/internal/agent/chat/requests/request%2Fforeign/turns/turn-1/inspection/inputs/source_1/content"));
        assertFalse(allowed("GET", "/internal/agent/chat/requests/request-1/turns/turn-1/inspection/inputs/source_1/content;admin=true"));
    }

    private static boolean allowed(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return AgentRuntimeAuthenticationFilter.allowed(request);
    }
}
