package cn.jia.chat.config;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.agent.service.AgentService;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FilterChainProxy regression for the API-key websocket contract beside the runtime chain. */
class AgentRuntimeWebSocketSecurityChainRegressionTest {
    private static final String AGENT_ID = "agt_" + "a".repeat(32);
    private static final String RUNTIME_TOKEN = "1".repeat(32);

    private AnnotationConfigWebApplicationContext context;
    private ApiKeyService keys;
    private AgentService agents;
    private RouteProbe routes;
    private MockMvc mvc;

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @Import(AgentRuntimeSecurityConfiguration.class)
    static class Wiring {
        @Bean AgentRuntimeAuthenticationService runtimeAuthenticationService() {
            return mock(AgentRuntimeAuthenticationService.class);
        }
        @Bean ApiKeyService apiKeyService() { return mock(ApiKeyService.class); }
        @Bean AgentService agentService() { return mock(AgentService.class); }

        @Bean
        @SuppressWarnings("unchecked")
        ApiKeyHandshakeInterceptor apiKeyHandshakeInterceptor(ApiKeyService keys, AgentService agents) {
            ObjectProvider<ApiKeyService> keyProvider = mock(ObjectProvider.class);
            ObjectProvider<AgentService> agentProvider = mock(ObjectProvider.class);
            when(keyProvider.getIfAvailable()).thenReturn(keys);
            when(agentProvider.getIfAvailable()).thenReturn(agents);
            return new ApiKeyHandshakeInterceptor(keyProvider, agentProvider);
        }

        @Bean RouteProbe routeProbe(ApiKeyHandshakeInterceptor interceptor) {
            return new RouteProbe(interceptor);
        }

        @Bean
        @Order(100)
        SecurityFilterChain ordinaryApplicationSecurityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

    @RestController
    static class RouteProbe {
        private final ApiKeyHandshakeInterceptor interceptor;
        private final WebSocketHandler webSocketHandler = mock(WebSocketHandler.class);
        private final AtomicInteger ordinaryRouteHits = new AtomicInteger();

        RouteProbe(ApiKeyHandshakeInterceptor interceptor) { this.interceptor = interceptor; }

        @GetMapping("/ws/agent/channel")
        void websocket(HttpServletRequest request, HttpServletResponse response) {
            ordinaryRouteHits.incrementAndGet();
            var serverRequest = new ServletServerHttpRequest(request);
            var serverResponse = new ServletServerHttpResponse(response);
            var attributes = new HashMap<String, Object>();
            boolean accepted = interceptor.beforeHandshake(
                    serverRequest, serverResponse, webSocketHandler, attributes);
            if (accepted) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                interceptor.afterHandshake(serverRequest, serverResponse, webSocketHandler, null);
            }
        }

        @RequestMapping({"/oauth/token", "/resource", "/operator/reassign", "/general/ping"})
        void ordinary(HttpServletResponse response) {
            ordinaryRouteHits.incrementAndGet();
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Wiring.class);
        context.refresh();
        keys = context.getBean(ApiKeyService.class);
        agents = context.getBean(AgentService.class);
        routes = context.getBean(RouteProbe.class);
        mvc = MockMvcBuilders.standaloneSetup(routes)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();

        var key = new OauthApiKeyEntity().setId("key-a").setApiKey("valid-api-key")
                .setKeyName("native").setStatus(1).setClientId("client-a").setJiacn("tenant-a");
        when(keys.findByApiKey("valid-api-key")).thenReturn(key);
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void apiKeyWebSocketHeaderAndQueryAgentIdsReachTheActualHandshakeInterceptor() throws Exception {
        mvc.perform(get("/ws/agent/channel")
                        .header("X-API-Key", "valid-api-key")
                        .header("X-Agent-Id", AGENT_ID))
                .andExpect(status().isNoContent());
        mvc.perform(get("/ws/agent/channel")
                        .queryParam("api_key", "valid-api-key")
                        .queryParam("agentId", AGENT_ID))
                .andExpect(status().isNoContent());

        assertEquals(2, routes.ordinaryRouteHits.get());
        verify(keys, org.mockito.Mockito.times(2)).findByApiKey("valid-api-key");
        verify(agents, org.mockito.Mockito.times(2))
                .requireApiKeyOwnedAgent("client-a", "tenant-a", AGENT_ID);
    }

    @Test
    void runtimeCredentialSignalsCannotReachWebSocketOauthOperatorOrGeneralRoutes() throws Exception {
        for (String path : new String[] {
                "/ws/agent/channel", "/oauth/token", "/resource", "/operator/reassign", "/general/ping"
        }) {
            mvc.perform(get(path)
                            .header("X-API-Key", "valid-api-key")
                            .header("X-Agent-Id", AGENT_ID)
                            .header("X-Agent-Runtime-Id", "runtime-a")
                            .header("Authorization", "AgentRuntime " + RUNTIME_TOKEN))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/oauth/token")
                        .header("X-Agent-Id", AGENT_ID)
                        .header("X-Agent-Runtime-Id", "runtime-a"))
                .andExpect(status().isForbidden());

        assertEquals(0, routes.ordinaryRouteHits.get());
        verifyNoInteractions(keys, agents);
    }

    @Test
    void runtimeIdAndMalformedRuntimeAuthorizationStillSelectFailClosedLane() throws Exception {
        mvc.perform(get("/ws/agent/channel")
                        .header("X-API-Key", "valid-api-key")
                        .header("X-Agent-Id", AGENT_ID)
                        .header("X-Agent-Id", "agt_" + "b".repeat(32)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/ws/agent/channel")
                        .header("X-API-Key", "valid-api-key")
                        .header("X-Agent-Id", AGENT_ID)
                        .header("X-Agent-Runtime-Id", "runtime-a"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/ws/agent/channel")
                        .header("X-API-Key", "valid-api-key")
                        .header("X-Agent-Id", AGENT_ID)
                        .header("Authorization", "AgentRuntimeMalformed"))
                .andExpect(status().isForbidden());

        assertEquals(0, routes.ordinaryRouteHits.get());
        verify(keys, never()).findByApiKey(any());
        verifyNoInteractions(agents);
    }
}
