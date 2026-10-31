package cn.jia.chat.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatMaterialsInputService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Binary Accept must not hide JSON failures behind an error dispatch. */
class AgentMaterialsErrorHttpTest {
    private static final String PATH = "/internal/agent/chat/requests/request/turns/turn/materials/inputs/source/content";
    private final ChatMaterialsInputService authority = mock(ChatMaterialsInputService.class);
    private final AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AgentMaterialsInputController(authority, authentication)).build();

    @org.junit.jupiter.api.BeforeEach void fences() {
        when(authentication.withNativeFence(any(),any()))
                .thenAnswer(i -> ((java.util.function.Supplier<?>)i.getArgument(1)).get());
    }

    @Test void anonymousImageRequestKeepsUnauthorizedJson() throws Exception {
        mvc.perform(get(PATH).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isUnauthorized()).andExpect(content().contentType(MediaType.APPLICATION_JSON));
        verifyNoInteractions(authority);
    }

    @Test void humanCredentialStillCannotReadMaterial() throws Exception {
        var human = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated("human", "token", java.util.List.of());
        mvc.perform(get(PATH).principal(human).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isForbidden()).andExpect(content().contentType(MediaType.APPLICATION_JSON));
        verifyNoInteractions(authority);
    }

    @Test void unavailableMaterialKeepsNotFoundJson() throws Exception {
        failsWith(new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Material input is unavailable"), 404);
    }

    @Test void conflictAndUnavailableKeepTheirOwnStatuses() throws Exception {
        failsWith(new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, "Changed"), 409);
        failsWith(new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR, "Unavailable"), 503);
    }

    @Test void serviceFailureKeepsServiceUnavailableJson() throws Exception {
        failsWith(new IllegalStateException("private diagnostic must not reach response"), 503);
    }

    @Test void successfulImageStillReturnsExactBinaryBytes() throws Exception {
        var runtime = runtime();
        byte[] bytes = new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
        when(authority.read(any()))
                .thenReturn(new cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeDownload(
                        "image.png", "image/png", "a".repeat(64), bytes.length, new java.io.ByteArrayInputStream(bytes)));
        mvc.perform(get(PATH).principal(runtime).accept(MediaType.IMAGE_PNG)
                        .header("X-Materials-Manifest-Digest", "sha256:" + "a".repeat(64)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes));
        verify(authentication,times(2)).withNativeFence(eq(runtime),any());
        verify(authority).revalidate(any());
    }

    private AgentRuntimeAuthentication runtime() {
        var runtime = mock(AgentRuntimeAuthentication.class);
        var scope = new AgentRuntimeAuthentication.Scope("0", "client", "owner", "agent", "runtime");
        when(runtime.isAuthenticated()).thenReturn(true);
        when(runtime.getPrincipal()).thenReturn(scope);
        when(runtime.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(scope,
                "rti_" + "a".repeat(32), "host", 1L, "a".repeat(64), 1L, 1L));
        return runtime;
    }

    private void failsWith(RuntimeException failure, int statusCode) throws Exception {
        var runtime = runtime();
        var scope = runtime.getPrincipal();
        when(authority.prepare(eq(scope), eq("request"), eq("turn"), eq("source"), anyString())).thenThrow(failure);
        mvc.perform(get(PATH).principal(runtime).accept(MediaType.IMAGE_PNG)
                        .header("X-Materials-Manifest-Digest", "sha256:" + "a".repeat(64)))
                .andExpect(status().is(statusCode)).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private diagnostic"))));
    }
}
