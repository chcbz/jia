package cn.jia.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class AgentConversationToolsRouteTest {
    private static final String ROOT="/internal/agent/chat/requests/req-1/turns/turn-1/tools";
    @Test void onlyExactReadCatalogAndPostToolRoutesAreAllowed() {
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("GET",ROOT)));
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST",ROOT+"/skill_read")));
        for(String method:new String[]{"PUT","DELETE","PATCH"})
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest(method,ROOT+"/skill_read")));
        for(String suffix:new String[]{"/skill_read/extra","/../other","/skill%2fread","/"})
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST",ROOT+suffix)));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST",ROOT)));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("GET",ROOT+"/skill_read")));
    }
    @Test void routesNeverFallBackToOwnerJwtEvenWithoutRuntimeHeaders() {
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(new MockHttpServletRequest("GET",ROOT)));
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(new MockHttpServletRequest("POST",ROOT+"/skill_read")));
    }
}
