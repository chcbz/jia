package cn.jia.agent.config;

import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class SkillPackageSecurityConfigurationTest {
    @Test void packagePathAlwaysSelectsSingleNativeLaneIncludingMissingCredentials() {
        var request=new MockHttpServletRequest("GET","/internal/agent/skill-installations/si_1/package");
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(request));
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(request));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        assertEquals(0,SkillPackageSecurityConfiguration.class.getDeclaredMethods().length);
    }
    @Test void legacyKeyCannotAuthenticatePackageAndMethodOrEncodedPathIsRejected() {
        var request=new MockHttpServletRequest("GET","/internal/agent/skill-installations/si_1/package");
        request.addHeader("X-API-Key","historical-only");
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeAuthenticationFilter.sessionHeaders(request));
        request.setMethod("POST");assertFalse(AgentRuntimeAuthenticationFilter.allowed(request));
        request.setMethod("GET");request.setRequestURI("/internal/agent/skill-installations/si_1%2fother/package");
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(request));
    }
}
