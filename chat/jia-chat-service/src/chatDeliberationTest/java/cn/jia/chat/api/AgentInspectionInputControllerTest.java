package cn.jia.chat.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.service.ChatInspectionAuthorityService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentInspectionInputControllerTest {
    private final ChatInspectionAuthorityService authority = mock(ChatInspectionAuthorityService.class);
    private final AgentInspectionInputController controller = new AgentInspectionInputController(authority);

    @Test
    void humanJwtAndAnonymousNeverReachMachineAuthority() {
        MockHttpServletRequest request = request();
        var human = UsernamePasswordAuthenticationToken.authenticated("human", "jwt", java.util.List.of());
        assertThrows(SecurityException.class,
                () -> controller.content("request", "turn", "source", human, request));
        assertThrows(SecurityException.class,
                () -> controller.content("request", "turn", "source", null, request));
        verify(authority, never()).read(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void runtimeResponseIsNoStoreNosniffAndExactBytes() {
        AgentRuntimeAuthentication runtime = mock(AgentRuntimeAuthentication.class);
        AgentRuntimeAuthentication.Scope scope =
                new AgentRuntimeAuthentication.Scope("0", "client", "owner", "agent", "runtime");
        when(runtime.getPrincipal()).thenReturn(scope);
        byte[] bytes = "bird\n".getBytes(StandardCharsets.UTF_8);
        when(authority.read(scope, "request", "turn", "source", digest()))
                .thenReturn(new ChatInspectionAuthorityService.Content("bird.txt", "text/plain", bytes));
        var response = controller.content("request", "turn", "source", runtime, request());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("text/plain", response.getHeaders().getFirst("Content-Type"));
        assertArrayEquals(bytes, response.getBody());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/internal/agent/chat/requests/request/turns/turn/inspection/inputs/source/content");
        request.addHeader("X-Inspection-Manifest-Digest", digest());
        return request;
    }

    private static String digest() {
        return "sha256:" + "a".repeat(64);
    }
}
