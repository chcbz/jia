package cn.jia.chat.handler;

import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Delivery/readiness slice; persistent fence authority is mocked, not claimed as cloud evidence. */
class AgentWebSocketUnifiedReadinessTest {
    @Test void socketPresenceAndHeartbeatDoNotAdmitExecution() throws Exception {
        var f = new Fixture(); var session = f.session("socket", "agent-a");
        f.handler.afterConnectionEstablished(session);
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
    }
    @Test void durableStateUnhealthyDoesNotStarveReadySibling() throws Exception {
        var f = new Fixture(); var a = f.session("socket-a", "agent-a"); var b = f.session("socket-b", "agent-b");
        f.register(a, false, true); f.register(b, true, true);
        clearInvocations(a, b);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
        assertEquals(AgentRawCommandDispatchResult.Status.SENT, f.dispatch("agent-b").status());
        verify(a, never()).sendMessage(any()); verify(b).sendMessage(any(TextMessage.class));
    }
    @Test void disabledExecutionAdapterIsNotMadeReadyByHealthyQueue() throws Exception {
        var f = new Fixture(); var a = f.session("socket-a", "agent-a"); f.register(a, true, false);
        clearInvocations(a);
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
        verify(a, never()).sendMessage(any());
    }
    @Test void staleChannelDoesNotReceiveOriginalCommandButCurrentChannelReceivesExactBytesOnce() throws Exception {
        var f = new Fixture(); var old = f.session("old", "agent-a"); var current = f.session("new", "agent-a");
        f.register(old, true, true); f.register(current, true, true); f.current.remove("old");
        clearInvocations(old, current);
        var result = f.dispatch("agent-a"); assertEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
        assertEquals(1, result.sentSessionCount()); verify(old, never()).sendMessage(any());
        var message = org.mockito.ArgumentCaptor.forClass(TextMessage.class); verify(current).sendMessage(message.capture());
        assertArrayEquals(f.wire("agent-a"), message.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
    }
    @Test void duplicateCurrentExecutionChannelsRejectInsteadOfBroadcast() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); var b = f.session("b", "agent-a");
        f.register(a, true, true); f.register(b, true, true); clearInvocations(a, b);
        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED, f.dispatch("agent-a").status());
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        verify(a, never()).sendMessage(any()); verify(b, never()).sendMessage(any());
    }
    @Test void receiptDeliveryFailureCannotActivateReadiness() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a");
        doThrow(new java.io.IOException("fixture transport failure")).when(a).sendMessage(any());
        f.register(a, true, true);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
    }
    @Test void reRegistrationWithBrokenDurableStateRetiresPreviousReadiness() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        f.register(a, false, true);
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
    }
    @Test void currentBindingIsRecheckedImmediatelyBeforeTransportSend() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        when(f.auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenReturn(true, false);
        clearInvocations(a);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
        verify(a, never()).sendMessage(any());
    }
    @Test void revokedAgentDoesNotDisableSibling() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); var b = f.session("b", "agent-b");
        f.register(a, true, true); f.register(b, true, true); f.current.remove("a");
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-b"));
    }
    @Test void undeclaredMediaAdapterDoesNotHideReadyCommandAgentFromActiveProjection() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        var runtime = new AgentRuntimeDTO(); runtime.setAgentId("agent-a");
        when(f.service.get("agent-a")).thenReturn(runtime);
        assertEquals(List.of(runtime), f.handler.getExecutionReadyAgents());
    }
    @Test void activeProjectionNeverFetchesUnreadyAgentsOrChangesCallerAcl() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, false);
        assertTrue(f.handler.getExecutionReadyAgents().isEmpty());
        verify(f.service, never()).get(any());
    }
    @Test void unknownOrDuplicateCommandTypesRejectRegistrationBeforeRuntimeMutation() throws Exception {
        for (var types : List.of(List.of("NEW_BUSINESS_COMMAND"), List.of("TASK_INVITE", "TASK_INVITE"))) {
            var f = new Fixture(); var a = f.session("a", "agent-a");
            f.handler.afterConnectionEstablished(a);
            f.handler.handleTextMessage(a, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                    "type", "agent.register", "agentId", "agent-a", "runtimeInstanceId", "boot",
                    "durableStateHealthy", true, "readyCommandTypes", types))));
            verify(f.service, never()).register(any());
            assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        }
    }
    @Test void unlistedCommandTypeCannotUseAnotherReadyAdapter() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        clearInvocations(a);
        var raw = new String(f.wire("agent-a"), StandardCharsets.UTF_8).replace("TASK_INVITE", "WORK_ITEM_EXECUTE").getBytes(StandardCharsets.UTF_8);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE,
                f.handler.dispatchExactRawCommand("0", "client", "task", "agent-a", raw).status());
        verify(a, never()).sendMessage(any());
    }
    @Test void unhealthyPresenceRetiresReadinessWithoutClosingSiblingChannel() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); var b = f.session("b", "agent-b");
        f.register(a, true, true); f.register(b, true, true);
        var runtime = new AgentRuntimeDTO(); runtime.setAgentId("agent-a"); runtime.setStatus("online");
        when(f.service.updateStatus(eq("agent-a"), any())).thenReturn(runtime);
        f.handler.handleTextMessage(a, new TextMessage("{\"schemaVersion\":1,\"messageType\":\"agent.presence\",\"messageId\":\"health\","
                + "\"agentId\":\"agent-a\",\"runtimeInstanceId\":\"boot\",\"status\":\"online\",\"durableStateHealthy\":false}"));
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-b"));
    }
    private static class Fixture {
        final AgentService service = mock(AgentService.class);
        final AgentRuntimeAuthenticationService auth = mock(AgentRuntimeAuthenticationService.class);
        final Set<String> current = new HashSet<>();
        final AgentWebSocketHandler handler;
        @SuppressWarnings("unchecked") Fixture() {
            ObjectProvider<AgentService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(service);
            when(service.register(any())).thenAnswer(inv -> new AgentRegisterResultDTO(
                    ((AgentRegisterDTO) inv.getArgument(0)).getAgentId(), "fixture-token", "online"));
            when(auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> current.contains(inv.getArgument(0)));
            handler = new AgentWebSocketHandler(mock(ChatClient.class), provider, mock(ChatMessageDao.class), mock(ChatConversationEventBroker.class));
            UnifiedRuntimeTestSupport.stub(auth);
            handler.setRuntimeAuthentication(auth);
        }
        WebSocketSession session(String id, String agent) {
            var s = mock(WebSocketSession.class);
            when(s.getId()).thenReturn(id); when(s.isOpen()).thenReturn(true);
            when(s.getAttributes()).thenReturn(new HashMap<>(Map.of("tenantId", "0", "clientId", "client", "jiacn", "owner", "agentId", agent, "runtimeInstanceId", "boot")));
            UnifiedRuntimeTestSupport.install(s);
            return s;
        }
        void register(WebSocketSession s, boolean durable, boolean execute) throws Exception {
            current.add(s.getId()); handler.afterConnectionEstablished(s);
            var payload = new HashMap<String, Object>(); payload.put("type", "agent.register"); payload.put("agentId", s.getAttributes().get("agentId"));
            UnifiedRuntimeTestSupport.registrationProof(s, payload);
            payload.put("runtimeInstanceId", "boot"); payload.put("durableStateHealthy", durable); payload.put("readyCommandTypes", execute ? List.of("TASK_INVITE") : List.of());
            handler.handleTextMessage(s, new TextMessage(new ObjectMapper().writeValueAsString(payload)));
        }
        AgentRawCommandDispatchResult dispatch(String agent) { return handler.dispatchExactRawCommand("0", "client", "task", agent, wire(agent)); }
        byte[] wire(String agent) {
            return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\",\"messageId\":\"original-message\",\"commandId\":\"original-command\","
                    + "\"tenantId\":\"0\",\"clientId\":\"client\",\"taskId\":\"task\",\"targetAgentId\":\"" + agent + "\","
                    + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,\"expiresAt\":2000000,\"payload\":{\"instruction\":\"fixture\"}}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
