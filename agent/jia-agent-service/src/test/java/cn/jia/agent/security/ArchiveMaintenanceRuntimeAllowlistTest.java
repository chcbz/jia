package cn.jia.agent.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveMaintenanceRuntimeAllowlistTest {
    private static final String PATH = "/internal/archive/v1/jobs/job-1/runs/run-1/blocks/chapter-1";

    @Test
    void exactArchiveRoutesIncludingPublishAreAllowedButAdjacentPathsAreNot() {
        assertTrue(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/context"));
        assertTrue(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/draft"));
        assertTrue(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/sources/src-1/content"));
        assertFalse(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/sources/%2f/content"));
        assertTrue(allowed("PUT", "/internal/archive/v1/jobs/job-1/runs/run-1/blocks/chapter-1"));
        assertFalse(allowed("PUT", "/internal/archive/v1/jobs/job-1/runs/run-1/draft"));
        assertFalse(allowed("PUT", "/internal/archive/v1/jobs/job-1/runs/run-1/draft/blocks/chapter-1"));
        assertTrue(allowed("POST", "/internal/archive/v1/jobs/job-1/runs/run-1/start"));
        assertTrue(allowed("POST", "/internal/archive/v1/jobs/job-1/runs/run-1/failure"));
        assertTrue(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/result"));
        assertTrue(allowed("POST", "/internal/archive/v1/jobs/job-1/runs/run-1/validate"));
        assertTrue(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/validation"));
        assertFalse(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/start"));
        assertFalse(allowed("POST", "/internal/archive/v1/jobs/job-1/runs/run-1/result"));
        assertFalse(allowed("POST", "/internal/archive/v1/jobs/job-1/runs/run-1/failure/extra"));
        assertTrue(allowed("POST", "/internal/archive/v1/jobs/job-1/runs/run-1/publish"));
        assertFalse(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/publish"));
        assertFalse(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/context/extra"));
        assertFalse(allowed("GET", "/internal/archive/v1/jobs/job-1/runs/run-1/%2e%2e/context"));
        assertFalse(allowed("PUT", "/internal/archive/v1/jobs/job-1/runs/run-1/blocks/chapter-1/extra"));
    }

    @Test
    void mergedPlatformAndConversationRuntimeLanesRemainExactAlongsideArchive() {
        assertTrue(allowed("GET", "/internal/agent/platform-skills/installations/psi-a/package"));
        assertTrue(allowed("POST", "/internal/agent/platform-skills/installations/psi-a/result"));
        assertTrue(allowed("GET", "/internal/agent/tasks/conversation-executions/commands"));
        assertTrue(allowed("GET", "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands"));
        assertTrue(allowed("POST", "/internal/agent/tasks/task-a/runs/run-a/conversation/inputs-v3"));
        assertTrue(allowed("POST", "/internal/agent/tasks/task-a/runs/run-a/conversation/provider-start-controlled-image-v3"));
        assertFalse(allowed("POST", "/internal/agent/platform-skills/installations/psi-a/package"));
        assertFalse(allowed("GET", "/internal/agent/tasks/task-a/runs/run-a/conversation/inputs-v3"));
        assertFalse(allowed("POST", "/internal/agent/tasks/task-a/runs/run-a/conversation/inputs-v3/extra"));
        assertFalse(allowed("GET", "/archive/admin/v1/collections/platform-classics/jobs"));
    }

    @Test
    void originAndDuplicateIdentityHeadersFailClosed() throws Exception {
        AgentRuntimeAuthenticationService service = mock(AgentRuntimeAuthenticationService.class);
        AgentRuntimeAuthenticationFilter filter = new AgentRuntimeAuthenticationFilter(service);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest origin = request("PUT", PATH);
        origin.addHeader("Origin", "https://browser.invalid");
        MockHttpServletResponse originResponse = new MockHttpServletResponse();
        filter.doFilter(origin, originResponse, chain);
        assertEquals(403, originResponse.getStatus());
        verify(chain, never()).doFilter(any(), any());

        MockHttpServletRequest duplicate = request("PUT", PATH);
        duplicate.addHeader("X-Agent-Id", "agent-other");
        MockHttpServletResponse duplicateResponse = new MockHttpServletResponse();
        filter.doFilter(duplicate, duplicateResponse, chain);
        assertEquals(401, duplicateResponse.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void exactRuntimeCredentialAuthenticatesAndReachesChain() throws Exception {
        AgentRuntimeAuthenticationService service = mock(AgentRuntimeAuthenticationService.class);
        AgentRuntimeAuthentication authentication = new AgentRuntimeAuthentication(
                new AgentRuntimeAuthentication.Scope("0", "client-a", "owner-a", "agent-a", "runtime-a"));
        when(service.authenticate("agent-a", "runtime-a", "0123456789abcdef0123456789abcdef"))
                .thenReturn(authentication);
        AgentRuntimeAuthenticationFilter filter = new AgentRuntimeAuthenticationFilter(service);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("PUT", PATH), response, chain);
        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }

    private boolean allowed(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return AgentRuntimeAuthenticationFilter.allowed(request);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "AgentRuntime 0123456789abcdef0123456789abcdef");
        request.addHeader("X-Agent-Id", "agent-a");
        request.addHeader("X-Agent-Runtime-Id", "runtime-a");
        return request;
    }
}
