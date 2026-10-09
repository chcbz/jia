package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketCapabilityNegotiationTest {
    @Test
    void exactTenantOwnerClientAgentBindingNeverInheritsForeignCapability() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession exact = session("exact", "tenant-a", "owner-a", "client-a", "agent-a");
        WebSocketSession foreignOwner = session("foreign", "tenant-a", "owner-b", "client-a", "agent-a");
        bind(handler, exact, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true)));
        bind(handler, foreignOwner, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true)));

        var result = handler.sendNegotiatedChatMessageToAgent(
                "tenant-a", "owner-a", "client-a", "agent-a", InteractionRoute.CHAT, wire());

        assertEquals(AgentWebSocketHandler.CapabilityDispatchStatus.READY, result.status());
        assertTrue(result.delivered());
        verify(exact).sendMessage(any(TextMessage.class));
        verify(foreignOwner, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void modernDisabledDeclarationSuppressesLegacyFallback() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession modern = session("modern", "tenant-a", "owner-a", "client-a", "agent-a");
        WebSocketSession legacy = session("legacy", "tenant-a", "owner-a", "client-a", "agent-a");
        bind(handler, modern, AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, false)));
        bind(handler, legacy, AgentRuntimeCapabilities.legacy());

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

    private AgentWebSocketHandler handler() {
        return new AgentWebSocketHandler(mock(ChatClient.class), mock(ObjectProvider.class),
                mock(ChatMessageDao.class), new ChatConversationEventBroker());
    }

    private WebSocketSession session(String id, String tenant, String owner, String client, String agent) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of(
                "tenantId", tenant, "jiacn", owner, "clientId", client, "agentId", agent)));
        return session;
    }

    @SuppressWarnings("unchecked")
    private void bind(AgentWebSocketHandler handler, WebSocketSession session,
            AgentRuntimeCapabilities capabilities) throws Exception {
        ((Map<String, WebSocketSession>) field(handler, "sessions")).put(session.getId(), session);
        ((Map<String, Set<String>>) field(handler, "sessionAgentIds"))
                .put(session.getId(), ConcurrentHashMap.newKeySet());
        ((Map<String, Set<String>>) field(handler, "sessionAgentIds")).get(session.getId()).add("agent-a");
        ((Map<String, Set<String>>) field(handler, "successfullyRegisteredAgentIds"))
                .put(session.getId(), ConcurrentHashMap.newKeySet());
        ((Map<String, Set<String>>) field(handler, "successfullyRegisteredAgentIds"))
                .get(session.getId()).add("agent-a");
        ((Map<String, AgentRuntimeCapabilities>) field(handler, "sessionRuntimeCapabilities"))
                .put(session.getId(), capabilities);
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private Map<String, Object> wire() {
        return Map.of("schemaVersion", 1, "messageId", "evt-1",
                "messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE, "content", "hello");
    }
}
