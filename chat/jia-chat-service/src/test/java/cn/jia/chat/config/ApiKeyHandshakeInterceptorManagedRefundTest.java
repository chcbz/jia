package cn.jia.chat.config;

import cn.jia.agent.service.AgentService;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyHandshakeInterceptorManagedRefundTest {
    @Test
    void refundedManagedKeyWithDisabledStatusCannotHandshake() {
        ApiKeyService keys = mock(ApiKeyService.class);
        @SuppressWarnings("unchecked") ObjectProvider<ApiKeyService> keyProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentService> agentProvider = mock(ObjectProvider.class);
        when(keyProvider.getIfAvailable()).thenReturn(keys);
        OauthApiKeyEntity disabled = new OauthApiKeyEntity().setApiKey("managed-secret").setKeyName("hosting:hri-one")
                .setStatus(0).setClientId("Client-A").setJiacn("Owner-A");
        when(keys.findByApiKey("managed-secret")).thenReturn(disabled);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "managed-secret");
        headers.set("X-Agent-Id", "agt_0123456789abcdef0123456789abcdef");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("ws://localhost/openclaw"));

        boolean accepted = new ApiKeyHandshakeInterceptor(keyProvider, agentProvider).beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verify(agentProvider, never()).getIfAvailable();
    }
}
