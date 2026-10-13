package cn.jia.chat.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.service.ChatMaterialsInputService;
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

class AgentMaterialsInputControllerTest {
    private final ChatMaterialsInputService authority = mock(ChatMaterialsInputService.class);
    private final cn.jia.agent.security.AgentRuntimeAuthenticationService authentication = mock(cn.jia.agent.security.AgentRuntimeAuthenticationService.class);
    private final AgentMaterialsInputController controller = new AgentMaterialsInputController(authority,authentication);

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
        when(runtime.getPrincipal()).thenReturn(scope);when(runtime.isAuthenticated()).thenReturn(true);
        var proof=new cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof(scope,
                "rti_"+"a".repeat(32),"host",1L,"a".repeat(64),1L,1L);
        when(runtime.getDetails()).thenReturn(proof);
        when(authentication.withNativeFence(org.mockito.ArgumentMatchers.eq(runtime),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i->((java.util.function.Supplier<?>)i.getArgument(1)).get());
        byte[] bytes = "bird\n".getBytes(StandardCharsets.UTF_8);
        when(authority.read(scope, "request", "turn", "source", digest()))
                .thenReturn(new cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeContent("bird.txt", "text/plain", bytes));
        var response = controller.content("request", "turn", "source", runtime, request());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("text/plain", response.getHeaders().getFirst("Content-Type"));
        assertArrayEquals(bytes, response.getBody());
    }

    @Test void duplicateDigestQueryAndChunkedBodyNeverReachAuthority() {
        var runtime=mock(AgentRuntimeAuthentication.class);when(runtime.isAuthenticated()).thenReturn(true);
        var duplicate=request();duplicate.addHeader("X-Materials-Manifest-Digest",digest());
        assertThrows(RuntimeException.class,()->controller.content("request","turn","source",runtime,duplicate));
        var query=request();query.setQueryString("extra=1");
        assertThrows(RuntimeException.class,()->controller.content("request","turn","source",runtime,query));
        var body=request();body.addHeader("Transfer-Encoding","chunked");
        assertThrows(RuntimeException.class,()->controller.content("request","turn","source",runtime,body));
        org.mockito.Mockito.verifyNoInteractions(authority,authentication);
    }
    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/internal/agent/chat/requests/request/turns/turn/materials/inputs/source/content");
        request.addHeader("X-Materials-Manifest-Digest", digest());
        return request;
    }

    private static String digest() {
        return "sha256:" + "a".repeat(64);
    }
}
