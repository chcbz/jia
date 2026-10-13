package cn.jia.chat.config;

import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.agent.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.socket.WebSocketHandler;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual chain routing: WS is native-only; neither API keys nor Runtime credentials reach ordinary APIs. */
class AgentRuntimeWebSocketSecurityChainRegressionTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    @Configuration(proxyBeanMethods = false) @EnableWebSecurity @Import(AgentRuntimeSecurityConfiguration.class)
    static class Wiring {
        @Bean AgentRuntimeAuthenticationService auth() { return mock(AgentRuntimeAuthenticationService.class); }
        @Bean AgentRuntimeHandshakeInterceptor interceptor(AgentRuntimeAuthenticationService auth) { return new AgentRuntimeHandshakeInterceptor(auth); }
        @Bean Probe probe(AgentRuntimeHandshakeInterceptor interceptor) { return new Probe(interceptor); }
        @Bean @Order(100) SecurityFilterChain ordinary(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(AbstractHttpConfigurer::disable); return http.build();
        }
    }
    @RestController static class Probe {
        final AgentRuntimeHandshakeInterceptor interceptor;
        Probe(AgentRuntimeHandshakeInterceptor interceptor) { this.interceptor = interceptor; }
        @GetMapping("/ws/agent/channel") void websocket(HttpServletRequest request, HttpServletResponse response) {
            if (interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response),
                    mock(WebSocketHandler.class), new HashMap<>())) response.setStatus(204);
        }
        @RequestMapping({"/oauth/token", "/resource", "/operator/reassign", "/general/ping"})
        void ordinary(HttpServletResponse response) { response.setStatus(204); }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Wiring.class); context.refresh();
        var service = context.getBean(AgentRuntimeAuthenticationService.class);
        var scope = new AgentRuntimeAuthentication.Scope("0", "client", "owner", AgentRuntimeHandshakeInterceptorTest.AGENT, "boot");
        var proof = new AgentRuntimeAuthenticationService.Proof(scope, AgentRuntimeHandshakeInterceptorTest.INSTALLATION, "host", 3, "d".repeat(64), 7, 2);
        var principal = mock(AgentRuntimeAuthentication.class);
        when(principal.getPrincipal()).thenReturn(scope); when(principal.isAuthenticated()).thenReturn(true);
        when(principal.getAuthorities()).thenReturn(List.of(new SimpleGrantedAuthority("AGENT_RUNTIME_NARROW")));
        when(principal.getDetails()).thenReturn(proof);
        when(service.authenticate(any(AgentRuntimeAuthenticationFilter.SessionHeaders.class), eq(false))).thenReturn(principal);
        when(service.verify(any())).thenReturn(proof);
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(Probe.class)).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { context.close(); }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder nativeRequest(String path) {
        return get(path).header("Authorization", "AgentRuntime rts1_" + "b".repeat(64))
                .header("X-Agent-Id", AgentRuntimeHandshakeInterceptorTest.AGENT)
                .header("X-Agent-Installation-Id", AgentRuntimeHandshakeInterceptorTest.INSTALLATION)
                .header("X-Agent-Host-Id", "host").header("X-Agent-Runtime-Id", "boot").header("X-Agent-Session-Generation", "3");
    }
    @Test void nativePendingSessionCanReachHandshakeWithoutOldRegisteredSocket() throws Exception {
        mvc.perform(nativeRequest("/ws/agent/channel")).andExpect(status().isNoContent());
    }
    @Test void oldApiKeyWsAndUrlCredentialFallbackAreGone() throws Exception {
        mvc.perform(get("/ws/agent/channel").header("X-API-Key", "legacy-secret").header("X-Agent-Id", AgentRuntimeHandshakeInterceptorTest.AGENT))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ws/agent/channel").queryParam("api_key", "legacy-secret").queryParam("agentId", AgentRuntimeHandshakeInterceptorTest.AGENT))
                .andExpect(status().isUnauthorized());
    }
    @Test void runtimeCredentialCannotReachOrdinaryMethodsOrRoutes() throws Exception {
        for (String path : List.of("/oauth/token", "/resource", "/operator/reassign", "/general/ping")) {
            mvc.perform(nativeRequest(path)).andExpect(status().isForbidden());
            mvc.perform(post(path).header("Authorization", "AgentRuntime rts1_" + "b".repeat(64))).andExpect(status().isForbidden());
        }
        mvc.perform(nativeRequest("/ws/agent/channel").header("Origin", "https://browser.invalid")).andExpect(status().isForbidden());
    }
    @Test void malformedOrDuplicateSessionProofIsClosedBeforeHandshake() throws Exception {
        mvc.perform(nativeRequest("/ws/agent/channel").header("X-Agent-Session-Generation", "4")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ws/agent/channel").header("Authorization", "AgentRuntime " + "b".repeat(32))).andExpect(status().isUnauthorized());
    }
}
