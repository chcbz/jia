package cn.jia.chat.config;

import cn.jia.agent.service.AgentService;
import cn.jia.core.common.EsConstants;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyHandshakeInterceptor implements HandshakeInterceptor {
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "api_key";
    private static final String AGENT_ID_HEADER = "X-Agent-Id";
    private static final String AGENT_ID_PARAM = "agent_id";
    private static final String AGENT_ID_CAMEL_PARAM = "agentId";

    private final ObjectProvider<ApiKeyService> apiKeyServiceProvider;
    private final ObjectProvider<AgentService> agentServiceProvider;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        ApiKeyService apiKeyService = apiKeyServiceProvider.getIfAvailable();
        if (apiKeyService == null) {
            log.warn("Reject OpenClaw websocket handshake: ApiKeyService is unavailable");
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        String apiKey = getApiKey(request);
        if (StringUtil.isEmpty(apiKey)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        OauthApiKeyEntity apiKeyEntity = apiKeyService.findByApiKey(apiKey);
        if (!isValid(apiKeyEntity)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String agentId = getAgentId(request);
        if (StringUtil.isEmpty(agentId)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            log.warn("Reject OpenClaw websocket handshake: AgentService is unavailable");
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        attributes.put("clientId", apiKeyEntity.getClientId());
        attributes.put("jiacn", apiKeyEntity.getJiacn());
        attributes.put("apiKeyName", apiKeyEntity.getKeyName());
        attributes.put("agentId", agentId);
        EsContext context = EsContextHolder.getContext();
        context.setClientId(apiKeyEntity.getClientId());
        context.setJiacn(apiKeyEntity.getJiacn());
        try {
            agentService.requireApiKeyOwnedAgent(apiKeyEntity.getClientId(), apiKeyEntity.getJiacn(), agentId);
        } catch (Exception e) {
            log.warn("Reject OpenClaw websocket handshake: agent ownership validation failed, agentId={}, clientId={}, jiacn={}",
                    agentId, apiKeyEntity.getClientId(), apiKeyEntity.getJiacn());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        EsContextHolder.setContext(new EsContext());
    }

    private String getApiKey(ServerHttpRequest request) {
        List<String> values = request.getHeaders().get(API_KEY_HEADER);
        if (values != null && !values.isEmpty() && !StringUtil.isEmpty(values.get(0))) {
            return values.get(0);
        }
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String value = servletRequest.getServletRequest().getParameter(API_KEY_PARAM);
            if (!StringUtil.isEmpty(value)) {
                return value;
            }
        }
        return UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(API_KEY_PARAM);
    }

    private String getAgentId(ServerHttpRequest request) {
        List<String> values = request.getHeaders().get(AGENT_ID_HEADER);
        if (values != null && !values.isEmpty() && !StringUtil.isEmpty(values.get(0))) {
            return values.get(0);
        }
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String value = servletRequest.getServletRequest().getParameter(AGENT_ID_PARAM);
            if (!StringUtil.isEmpty(value)) {
                return value;
            }
            value = servletRequest.getServletRequest().getParameter(AGENT_ID_CAMEL_PARAM);
            if (!StringUtil.isEmpty(value)) {
                return value;
            }
        }
        String value = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(AGENT_ID_PARAM);
        if (!StringUtil.isEmpty(value)) {
            return value;
        }
        return UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(AGENT_ID_CAMEL_PARAM);
    }

    private boolean isValid(OauthApiKeyEntity apiKeyEntity) {
        if (apiKeyEntity == null || !EsConstants.COMMON_YES.equals(apiKeyEntity.getStatus())) {
            return false;
        }
        return apiKeyEntity.getExpireTime() == null
                || !DateUtil.genDate(apiKeyEntity.getExpireTime()).before(new Date());
    }
}
