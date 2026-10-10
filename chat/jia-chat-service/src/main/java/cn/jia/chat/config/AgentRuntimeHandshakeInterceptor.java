package cn.jia.chat.config;

import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Native, installation-derived channel. Attributes contain only verified non-secret proof. */
@Component
public final class AgentRuntimeHandshakeInterceptor implements HandshakeInterceptor {
    public static final String PROOF_ATTRIBUTE = "agentRuntimeProof";
    private final AgentRuntimeAuthenticationService authentication;
    public AgentRuntimeHandshakeInterceptor(AgentRuntimeAuthenticationService authentication) { this.authentication = authentication; }

    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        try {
            if (!(request instanceof ServletServerHttpRequest servlet) || request.getURI().getRawQuery() != null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED); return false;
            }
            var proof = authentication.verify(AgentRuntimeAuthenticationFilter.sessionHeaders(servlet.getServletRequest()));
            var scope = proof.scope();
            attributes.put(PROOF_ATTRIBUTE, proof);
            attributes.put("tenantId", scope.tenantId()); attributes.put("clientId", scope.clientId());
            attributes.put("jiacn", scope.ownerJiacn()); attributes.put("agentId", scope.agentId());
            attributes.put("runtimeInstanceId", scope.runtimeInstanceId());
            attributes.put("runtimeInstallationId", proof.installationId()); attributes.put("runtimeHostId", proof.hostId());
            attributes.put("runtimeSessionGeneration", proof.sessionGeneration());
            return true;
        } catch (org.springframework.dao.DataAccessException unavailable) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE); return false;
        } catch (RuntimeException invalid) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED); return false;
        }
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }
}
