package cn.jia.chat.handler;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.config.AgentRuntimeHandshakeInterceptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fixture adapter only: production never infers credentials from legacy registration bytes. */
final class UnifiedRuntimeTestSupport {
    static final String INSTALLATION = "rti_" + "1".repeat(32);
    static void stub(AgentRuntimeAuthenticationService auth) {
        lenient().when(auth.withFence(any(), anyBoolean(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
    }
    static AgentWebSocketHandler authorize(AgentWebSocketHandler handler) {
        var auth = mock(AgentRuntimeAuthenticationService.class); stub(auth);
        lenient().when(auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenReturn(true);
        handler.setRuntimeAuthentication(auth); return handler;
    }
    static AgentRuntimeAuthenticationService.Proof install(WebSocketSession session) {
        var attrs = session.getAttributes();
        Object existing = attrs.get(AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE);
        if (existing instanceof AgentRuntimeAuthenticationService.Proof proof) return proof;
        // Tests that reject missing identity intentionally retain it; no body-selected authority.
        String agent = (String) attrs.getOrDefault("agentId", "missing-agent");
        String runtime = (String) attrs.getOrDefault("runtimeInstanceId", "runtime-1");
        attrs.putIfAbsent("runtimeInstanceId", runtime);
        var proof = new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope(
                (String) attrs.getOrDefault("tenantId", "0"), (String) attrs.getOrDefault("clientId", "missing-client"),
                (String) attrs.getOrDefault("jiacn", "missing-owner"), agent, runtime), INSTALLATION, "host-1", 1,
                "a".repeat(64), 1, 0);
        attrs.put(AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE, proof);
        return proof;
    }
    static void registrationProof(WebSocketSession session, Map<String, Object> payload) {
        var proof = install(session);
        payload.putIfAbsent("installationId", proof.installationId()); payload.putIfAbsent("hostId", proof.hostId());
        payload.putIfAbsent("sessionGeneration", proof.sessionGeneration());
        if (!Integer.valueOf(1).equals(payload.get("schemaVersion"))) payload.putIfAbsent("runtimeInstanceId", proof.scope().runtimeInstanceId());
    }
    static void deliver(AgentWebSocketHandler handler, WebSocketSession session, TextMessage message) throws Exception {
        install(session);
        String wire = message.getPayload();
        try {
            var mapper = new ObjectMapper();
            Map<String, Object> payload = mapper.readValue(wire, new TypeReference<HashMap<String, Object>>() { });
            if ("agent.register".equals(payload.get("type")) || "agent.register".equals(payload.get("messageType"))) {
                registrationProof(session, payload); wire = mapper.writeValueAsString(payload);
            }
        } catch (RuntimeException malformed) { /* preserve malformed-wire test bytes */ }
        handler.handleTextMessage(session, new TextMessage(wire));
    }
}
