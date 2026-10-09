package cn.jia.chat.config;

import cn.jia.agent.hosting.ManagedHostingCredentials;
import cn.jia.agent.service.AgentService;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyHandshakeInterceptorManagedOnboardingTest {
    private static final String AGENT_ID = "agt_0123456789abcdef0123456789abcdef";
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final AgentService agents = mock(AgentService.class);
    private final ManagedHostingCredentials managed = mock(ManagedHostingCredentials.class);
    private final ServerHttpRequest request = mock(ServerHttpRequest.class);
    private final ServerHttpResponse response = mock(ServerHttpResponse.class);
    private ApiKeyHandshakeInterceptor interceptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<ApiKeyService> keyProvider = mock(ObjectProvider.class);
        ObjectProvider<AgentService> agentProvider = mock(ObjectProvider.class);
        ObjectProvider<ManagedHostingCredentials> managedProvider = mock(ObjectProvider.class);
        when(keyProvider.getIfAvailable()).thenReturn(keys);
        when(agentProvider.getIfAvailable()).thenReturn(agents);
        when(managedProvider.getIfAvailable()).thenReturn(managed);
        interceptor = new ApiKeyHandshakeInterceptor(keyProvider, agentProvider, managedProvider);

        var key = new OauthApiKeyEntity().setId("31")
                .setApiKey("managed-secret").setKeyName("hosting:hri-one")
                .setStatus(1).setClientId("Client-A").setJiacn("Owner-A");
        key.setTenantId("0");
        when(keys.findByApiKey("managed-secret")).thenReturn(key);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "managed-secret");
        headers.set("X-Agent-Id", AGENT_ID);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("ws://localhost/openclaw"));
        doThrow(new IllegalStateException("identity is not active"))
                .when(agents).requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID);
    }

    @Test
    void exactManagedProvisionedAssociationMayReachFirstRegistration() {
        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertTrue(accepted);
        verify(managed).authorizeProvisionedHandshake(
                "0", "Client-A", "Owner-A", AGENT_ID, "31", "hosting:hri-one");
    }

    @Test
    void managedFallbackFailureRemainsForbidden() {
        doThrow(new IllegalStateException("not an exact managed association"))
                .when(managed).authorizeProvisionedHandshake(
                        "0", "Client-A", "Owner-A", AGENT_ID, "31", "hosting:hri-one");

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }
}
