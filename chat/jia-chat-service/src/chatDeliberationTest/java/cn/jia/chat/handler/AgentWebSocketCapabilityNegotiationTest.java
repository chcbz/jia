package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.config.AgentRuntimeHandshakeInterceptor;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.CanonicalContextJson;
import cn.jia.core.util.JsonUtil;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketCapabilityNegotiationTest {
    private final Map<AgentWebSocketHandler, RuntimeAuthority> authorities = new IdentityHashMap<>();

    @Test
    void exactTenantOwnerClientAgentBindingNeverInheritsForeignCapability() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession exact = session("exact", "tenant-a", "owner-a", "client-a", "agent-a");
        WebSocketSession foreignOwner = session("foreign", "tenant-a", "owner-b", "client-a", "agent-a");
        bind(handler, exact, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true)));
        bind(handler, foreignOwner, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true)));
        List<WebSocketSession> foreignScopes = List.of(
                session("foreign-tenant", "tenant-b", "owner-a", "client-a", "agent-a"),
                session("foreign-client", "tenant-a", "owner-a", "client-b", "agent-a"),
                session("foreign-agent", "tenant-a", "owner-a", "client-a", "agent-b"));
        for (WebSocketSession foreign : foreignScopes) {
            bind(handler, foreign, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true)));
        }

        var result = handler.sendNegotiatedChatMessageToAgent(
                "tenant-a", "owner-a", "client-a", "agent-a", InteractionRoute.CHAT, wire());

        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.READY, result.status());
        assertTrue(result.delivered());
        verify(exact).sendMessage(any(TextMessage.class));
        verify(foreignOwner, never()).sendMessage(any(TextMessage.class));
        for (WebSocketSession foreign : foreignScopes) verify(foreign, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void modernDisabledDeclarationSuppressesLegacyFallback() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession modern = session("modern", "tenant-a", "owner-a", "client-a", "agent-a");
        WebSocketSession legacy = session("legacy", "tenant-a", "owner-a", "client-a", "agent-a");
        // The old capability declaration is still present, but its authenticated generation
        // is retired before the disabled modern declaration becomes current.
        bind(handler, legacy, AgentRuntimeCapabilities.legacy());
        bind(handler, modern, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, false)));

        var result = handler.sendNegotiatedChatMessageToAgent(
                "tenant-a", "owner-a", "client-a", "agent-a", InteractionRoute.CHAT, wire());

        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.WAITING_DISABLED, result.status());
        assertFalse(result.delivered());
        verify(modern, never()).sendMessage(any(TextMessage.class));
        verify(legacy, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void legacyOnlyChatRemainsCompatible() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession legacy = session("legacy", "tenant-a", "owner-a", "client-a", "agent-a");
        bind(handler, legacy, AgentRuntimeCapabilities.legacy());
        var result = handler.sendNegotiatedChatMessageToAgent(
                "tenant-a", "owner-a", "client-a", "agent-a", InteractionRoute.CHAT, wire());
        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.LEGACY_COMPATIBLE, result.status());
        assertTrue(result.delivered());
        verify(legacy).sendMessage(any(TextMessage.class));
    }

    @Test
    void measuredTypedInspectionUsesExactSessionDespiteLegacyInspectUnavailable() throws Exception {
        AgentWebSocketHandler handler = handler();
        var registry = new TypedInspectionSessionRegistry();
        handler.setTypedInspectionSessions(registry);
        WebSocketSession selected = session("inspect", "tenant-a", "owner-a", "client-a", "agent-a");
        WebSocketSession foreign = session("foreign", "tenant-a", "owner-b", "client-a", "agent-a");
        var capabilities = AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true));
        bind(handler, selected, capabilities); bind(handler, foreign, capabilities);
        registry.register("inspect", "tenant-a", "owner-a", "client-a", "agent-a",
                TypedInspectionDeclarationTest.declaration(true), () -> true);
        registry.register("foreign", "tenant-a", "owner-b", "client-a", "agent-a",
                TypedInspectionDeclarationTest.declaration(true), () -> true);
        var result = handler.sendNegotiatedChatMessageToAgent("tenant-a", "owner-a", "client-a",
                "agent-a", InteractionRoute.INSPECT, inspectionWire());
        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.READY, result.status());
        assertTrue(result.delivered());
        assertEquals("INSPECT", result.negotiatedProfile().get("profile"));
        verify(selected).sendMessage(any(TextMessage.class));
        verify(foreign, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void typedInspectionDisabledStaleAmbiguousAndPolicyDriftNeverDispatch() throws Exception {
        for (String cause : java.util.List.of("missing", "disabled", "stale", "ambiguous", "policy-drift", "unregistered")) {
            AgentWebSocketHandler handler = handler();
            var registry = new TypedInspectionSessionRegistry();
            handler.setTypedInspectionSessions(registry);
            WebSocketSession selected = session("inspect", "tenant-a", "owner-a", "client-a", "agent-a");
            bind(handler, selected, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true)));
            if (!cause.equals("missing")) {
                var declaration = TypedInspectionDeclarationTest.declaration(!cause.equals("disabled"));
                if (cause.equals("policy-drift")) declaration.put("enginePolicyDigest", TypedInspectionDeclarationTest.digest('e'));
                registry.register(cause.equals("unregistered") ? "other" : "inspect", "tenant-a", "owner-a", "client-a",
                        "agent-a", declaration, () -> !cause.equals("stale"));
                if (cause.equals("ambiguous")) registry.register("duplicate", "tenant-a", "owner-a", "client-a",
                        "agent-a", declaration, () -> true);
            }
            var result = handler.sendNegotiatedChatMessageToAgent("tenant-a", "owner-a", "client-a",
                    "agent-a", InteractionRoute.INSPECT, inspectionWire());
            assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.UNSUPPORTED, result.status(), cause);
            assertFalse(result.delivered(), cause);
            verify(selected, never()).sendMessage(any(TextMessage.class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void measuredInspectionPreservesDeepSelectorsAndDataAcrossRootAndNestedEnvelope() throws Exception {
        AgentWebSocketHandler handler = handler();
        var registry = new TypedInspectionSessionRegistry();
        handler.setTypedInspectionSessions(registry);
        WebSocketSession selected = session("inspect", "tenant-a", "owner-a", "client-a", "agent-a");
        bind(handler, selected, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true)));
        registry.register("inspect", "tenant-a", "owner-a", "client-a", "agent-a",
                TypedInspectionDeclarationTest.declaration(true), () -> true);
        Map<String, Object> selector = Map.of("fileId", "owner-file", "version", "1");
        Map<String, Object> manifest = Map.of("profile", TypedInspectionDeclaration.parse(
                TypedInspectionDeclarationTest.declaration(true)).manifestProfile(),
                "sources", java.util.List.of(Map.of("selector", selector)));
        Map<String, Object> facts = Map.of("typedInspection", Map.of("manifest", manifest,
                "discussionFacts", Map.of("availableActions", java.util.List.of(
                        Map.of("inputMediaTypes", java.util.List.of("image", "text"))))),
                "attachmentText", "credential=literal-user-data");
        Map<String, Object> sourceVector = Map.of("conversationGeneration", "1");
        String contextHash = contextDigest(Map.of("sourceVector", sourceVector, "facts", facts));
        Map<String, Object> snapshot = Map.of("facts", facts, "sourceVector", sourceVector, "contextHash", contextHash);
        Map<String, Object> wire = new java.util.LinkedHashMap<>(wire());
        wire.put("contextSnapshot", snapshot);
        wire.put("payload", new java.util.LinkedHashMap<>(wire));
        // Demonstrate the production cause: the same map is truncated differently at depth 8.
        Map<String, Object> sanitized = JsonUtil.getMapper().readValue(JsonUtil.toSafeJson(wire), Map.class);
        assertFalse(sanitized.get("contextSnapshot").equals(
                ((Map<String, Object>) sanitized.get("payload")).get("contextSnapshot")));
        var result = handler.sendNegotiatedChatMessageToAgent("tenant-a", "owner-a", "client-a",
                "agent-a", InteractionRoute.INSPECT, wire);
        assertTrue(result.delivered());
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(selected).sendMessage(captor.capture());
        Map<String, Object> actual = JsonUtil.getMapper().readValue(captor.getValue().getPayload(), Map.class);
        assertEquals(snapshot, actual.get("contextSnapshot"));
        assertEquals(snapshot, ((Map<String, Object>) actual.get("payload")).get("contextSnapshot"));
        Map<String, Object> actualFacts = (Map<String, Object>) ((Map<String, Object>) actual.get("contextSnapshot")).get("facts");
        assertEquals(contextHash, contextDigest(Map.of("sourceVector", sourceVector, "facts", actualFacts)));
        assertEquals("credential=literal-user-data", actualFacts.get("attachmentText"));
    }

    @Test
    void authenticatedSocketWithoutSuccessfulRegistrationIsNotCapabilityReady() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession session = session("unregistered", "tenant-a", "owner-a", "client-a", "agent-a");
        var proof = authorities.get(handler).issue(session);
        session.getAttributes().put(AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE, proof);
        handler.afterConnectionEstablished(session);
        clearInvocations(session);
        var result = handler.sendNegotiatedChatMessageToAgent(
                "tenant-a", "owner-a", "client-a", "agent-a", InteractionRoute.CHAT, wire());
        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.OFFLINE, result.status());
        assertFalse(result.delivered());
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void staleGenerationAndUnhealthyRuntimeCannotBorrowReadyCapability() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession stale = session("stale", "tenant-a", "owner-a", "client-a", "agent-a");
        WebSocketSession unhealthy = session("unhealthy", "tenant-a", "owner-a", "client-a", "agent-a");
        var capabilities = AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true));
        bind(handler, stale, capabilities);
        register(handler, unhealthy, capabilities, false);
        var offline = handler.sendNegotiatedChatMessageToAgent(
                "tenant-a", "owner-a", "client-a", "agent-a", InteractionRoute.CHAT, wire());
        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.OFFLINE, offline.status());
        assertFalse(offline.delivered());
        verify(stale, never()).sendMessage(any(TextMessage.class));
        verify(unhealthy, never()).sendMessage(any(TextMessage.class));
        WebSocketSession ready = session("ready", "tenant-a", "owner-a", "client-a", "agent-a");
        bind(handler, ready, capabilities);
        var delivered = handler.sendNegotiatedChatMessageToAgent(
                "tenant-a", "owner-a", "client-a", "agent-a", InteractionRoute.CHAT, wire());
        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.READY, delivered.status());
        assertTrue(delivered.delivered());
        verify(ready).sendMessage(any(TextMessage.class));
        verify(stale, never()).sendMessage(any(TextMessage.class));
        verify(unhealthy, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ordinaryEventsKeepExistingSensitiveSerialization() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession selected = session("ordinary", "tenant-a", "owner-a", "client-a", "agent-a");
        var method = AgentWebSocketHandler.class.getDeclaredMethod("sendEvent",
                WebSocketSession.class, String.class, Map.class);
        method.setAccessible(true);
        assertEquals(true, method.invoke(handler, selected, "agent_registered", Map.of("password", "sensitive-fixture")));
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(selected).sendMessage(captor.capture());
        Map<String, Object> actual = JsonUtil.getMapper().readValue(captor.getValue().getPayload(), Map.class);
        assertTrue(actual.containsKey("password"));
        assertEquals(null, actual.get("password"));
    }

    private String contextDigest(Map<String, Object> value) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(CanonicalContextJson.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private Map<String, Object> inspectionWire() {
        Map<String, Object> wire = new java.util.LinkedHashMap<>(wire());
        wire.put("contextSnapshot", Map.of("facts", Map.of("typedInspection", Map.of("manifest",
                Map.of("profile", TypedInspectionDeclaration.parse(
                        TypedInspectionDeclarationTest.declaration(true)).manifestProfile())))));
        return wire;
    }

    @SuppressWarnings("unchecked")
    private AgentWebSocketHandler handler() {
        ObjectProvider<AgentService> provider = mock(ObjectProvider.class);
        AgentService agents = mock(AgentService.class);
        when(provider.getIfAvailable()).thenReturn(agents);
        when(agents.register(any())).thenAnswer(inv -> new AgentRegisterResultDTO(
                ((cn.jia.agent.entity.AgentRegisterDTO) inv.getArgument(0)).getAgentId(), null, "online"));
        var handler = new AgentWebSocketHandler(mock(ChatClient.class), provider,
                mock(ChatMessageDao.class), new ChatConversationEventBroker());
        RuntimeAuthority authority = new RuntimeAuthority();
        authorities.put(handler, authority);
        handler.setRuntimeAuthentication(authority.authentication);
        return handler;
    }

    private WebSocketSession session(String id, String tenant, String owner, String client, String agent) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of(
                "tenantId", tenant, "jiacn", owner, "clientId", client, "agentId", agent,
                "runtimeInstanceId", "boot-" + id)));
        return session;
    }

    private void bind(AgentWebSocketHandler handler, WebSocketSession session,
            AgentRuntimeCapabilities capabilities) throws Exception {
        register(handler, session, capabilities, true);
    }

    private void register(AgentWebSocketHandler handler, WebSocketSession session,
            AgentRuntimeCapabilities capabilities, boolean healthy) throws Exception {
        var proof = authorities.get(handler).issue(session);
        session.getAttributes().put(AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE, proof);
        handler.afterConnectionEstablished(session);
        clearInvocations(session);
        Map<String, Object> registration = new LinkedHashMap<>(Map.of(
                "schemaVersion", 1, "messageType", AgentProtocolConstants.TYPE_AGENT_REGISTER,
                "agentId", proof.scope().agentId(), "runtimeInstanceId", proof.scope().runtimeInstanceId(),
                "installationId", proof.installationId(), "hostId", proof.hostId(),
                "sessionGeneration", proof.sessionGeneration(), "durableStateHealthy", healthy,
                "readyCommandTypes", List.of()));
        // Missing modern CHAT capabilities is not missing Runtime authentication.
        if (capabilities.modern()) registration.put("runtimeCapabilities", capabilities.normalizedForReceipt());
        handler.handleTextMessage(session, new TextMessage(JsonUtil.getMapper().writeValueAsString(registration)));
        ArgumentCaptor<TextMessage> receipt = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(receipt.capture());
        assertTrue(receipt.getAllValues().stream().anyMatch(message ->
                message.getPayload().contains("\"type\":\"agent_registered\"")), "authenticated registration receipt");
        var scope = proof.scope();
        assertTrue(authorities.get(handler).authentication.isCurrentBinding(session.getId(), scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), scope.agentId(), scope.runtimeInstanceId()));
        clearInvocations(session);
    }

    /** Exact, stateful authority seam. Registration/receipt/readiness remain production code;
     * no blanket current-binding stub or reflection injection of successful registration. */
    private static final class RuntimeAuthority {
        private final AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
        private final Map<List<String>, AgentRuntimeAuthenticationService.Proof> current = new java.util.HashMap<>();
        private final Map<String, AgentRuntimeAuthenticationService.Proof> channels = new java.util.HashMap<>();

        private RuntimeAuthority() {
            doAnswer(inv -> {
                requireCurrent(inv.getArgument(0));
                return null;
            }).when(authentication).validateCurrent(any(AgentRuntimeAuthenticationService.Proof.class), anyBoolean());
            doAnswer(inv -> {
                requireCurrent(inv.getArgument(0));
                return ((Supplier<?>) inv.getArgument(2)).get();
            }).when(authentication).withFence(any(AgentRuntimeAuthenticationService.Proof.class), anyBoolean(), notNull());
            doAnswer(inv -> {
                String id = inv.getArgument(0);
                AgentRuntimeAuthenticationService.Proof proof = inv.getArgument(1);
                requireCurrent(proof);
                assertTrue(((java.util.function.BooleanSupplier) inv.getArgument(2)).getAsBoolean());
                channels.put(id, proof);
                var scope = proof.scope();
                return new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1", scope.tenantId(),
                        scope.clientId(), scope.ownerJiacn(), scope.agentId(), scope.runtimeInstanceId(),
                        proof.installationId(), proof.hostId(), proof.sessionGeneration(), true);
            }).when(authentication).bind(anyString(), any(AgentRuntimeAuthenticationService.Proof.class), notNull());
            doAnswer(inv -> { channels.remove(inv.getArgument(0)); return null; })
                    .when(authentication).disconnect(anyString());
            doAnswer(inv -> {
                var proof = channels.get(inv.<String>getArgument(0));
                if (proof == null || !proof.equals(current.get(subject(proof.scope())))) return false;
                return proof.scope().equals(new AgentRuntimeAuthentication.Scope(inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5)));
            }).when(authentication).isCurrentBinding(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        }

        private AgentRuntimeAuthenticationService.Proof issue(WebSocketSession session) {
            var attrs = session.getAttributes();
            var scope = new AgentRuntimeAuthentication.Scope((String) attrs.get("tenantId"), (String) attrs.get("clientId"),
                    (String) attrs.get("jiacn"), (String) attrs.get("agentId"), (String) attrs.get("runtimeInstanceId"));
            var prior = current.get(subject(scope));
            var proof = new AgentRuntimeAuthenticationService.Proof(scope, "rti_" + "1".repeat(32),
                    "host-fixture", prior == null ? 1 : prior.sessionGeneration() + 1, "a".repeat(64), 1, 0);
            current.put(subject(scope), proof);
            return proof;
        }

        private void requireCurrent(AgentRuntimeAuthenticationService.Proof proof) {
            if (proof == null || !proof.equals(current.get(subject(proof.scope()))))
                throw new IllegalArgumentException("AGENT_RUNTIME_UNAUTHENTICATED");
        }

        private static List<String> subject(AgentRuntimeAuthentication.Scope scope) {
            return List.of(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), scope.agentId());
        }
    }

    private Map<String, Object> wire() {
        return Map.of("schemaVersion", 1, "messageId", "evt-1",
                "messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE, "content", "hello");
    }
}
