package cn.jia.chat.handler;

import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketRawCommandDispatchTest extends BaseMockTest {
    @Mock ChatClient chatClient;
    @Mock AgentService agentService;
    @Mock ObjectProvider<AgentService> agentServiceProvider;
    @Mock ChatMessageDao chatMessageDao;
    @Mock ChatConversationEventBroker eventBroker;

    @Test
    void rawCommandGoesOnlyToExactTenantClientAndTargetSessionsWithoutWrappingOrReserialization()
            throws Exception {
        WebSocketSession exactOne = session("exact-1", "tenant-a", "client-a", "agent-1");
        WebSocketSession exactTwo = session("exact-2", "tenant-a", "client-a", "agent-1");
        WebSocketSession crossTenant = session("cross-tenant", "tenant-b", "client-a", "agent-1");
        WebSocketSession crossClient = session("cross-client", "tenant-a", "client-b", "agent-1");
        WebSocketSession otherAgent = session("other-agent", "tenant-a", "client-a", "agent-2");
        AgentWebSocketHandler handler = handler();
        register(handler, exactOne, "agent-1");
        register(handler, exactTwo, "agent-1");
        register(handler, crossTenant, "agent-1");
        register(handler, crossClient, "agent-1");
        register(handler, otherAgent, "agent-2");
        org.mockito.Mockito.clearInvocations(
                exactOne, exactTwo, crossTenant, crossClient, otherAgent);
        byte[] raw = wire("tenant-a", "client-a", "task-1", "agent-1");

        AgentRawCommandDispatchResult result = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", raw);

        assertEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
        assertEquals(2, result.matchingSessionCount());
        assertEquals(2, result.sentSessionCount());
        ArgumentCaptor<TextMessage> first = ArgumentCaptor.forClass(TextMessage.class);
        ArgumentCaptor<TextMessage> second = ArgumentCaptor.forClass(TextMessage.class);
        verify(exactOne).sendMessage(first.capture());
        verify(exactTwo).sendMessage(second.capture());
        assertArrayEquals(raw, first.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(raw, second.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
        assertEquals(new String(raw, StandardCharsets.UTF_8), first.getValue().getPayload());
        verify(crossTenant, never()).sendMessage(any(TextMessage.class));
        verify(crossClient, never()).sendMessage(any(TextMessage.class));
        verify(otherAgent, never()).sendMessage(any(TextMessage.class));
    }


    @Test
    void presenceBeforeRegisterIsOfflineForRawCommandAndRegisterMakesItReachable()
            throws Exception {
        WebSocketSession session = session(
                "presence-before-register", "tenant-a", "client-a", "agent-1");
        AgentRuntimeDTO presence = new AgentRuntimeDTO();
        presence.setAgentId("agent-1");
        presence.setStatus("online");
        when(agentService.updateStatus(any(), any())).thenReturn(presence);
        AgentWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"messageType":"agent.presence","messageId":"presence-1",
                 "agentId":"agent-1","sourceAgentId":"agent-1",
                 "runtimeInstanceId":"runtime-1","status":"online"}
                """));
        org.mockito.Mockito.clearInvocations(session);
        byte[] raw = wire("tenant-a", "client-a", "task-1", "agent-1");

        AgentRawCommandDispatchResult beforeRegister = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", raw);

        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, beforeRegister.status());
        assertEquals(0, beforeRegister.matchingSessionCount());
        verify(session, never()).sendMessage(any(TextMessage.class));

        register(handler, session, "agent-1");
        org.mockito.Mockito.clearInvocations(session);
        AgentRawCommandDispatchResult afterRegister = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", raw);

        assertEquals(AgentRawCommandDispatchResult.Status.SENT, afterRegister.status());
        assertEquals(1, afterRegister.matchingSessionCount());
        assertEquals(1, afterRegister.sentSessionCount());
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void compatibilityCommandDispatchAlsoRequiresSuccessfulRegistration() throws Exception {
        WebSocketSession session = session(
                "direct-before-register", "tenant-a", "client-a", "agent-1");
        AgentRuntimeDTO presence = new AgentRuntimeDTO();
        presence.setAgentId("agent-1");
        presence.setStatus("online");
        when(agentService.updateStatus(any(), any())).thenReturn(presence);
        when(agentService.listTaskMemberAgentIds("tenant-a", "client-a", "task-1"))
                .thenReturn(List.of("agent-1"));
        AgentWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"messageType":"agent.presence","messageId":"presence-direct",
                 "agentId":"agent-1","sourceAgentId":"agent-1",
                 "runtimeInstanceId":"runtime-1","status":"online"}
                """));
        org.mockito.Mockito.clearInvocations(session);
        Map<String, Object> command = Map.of(
                "schemaVersion", 1,
                "messageType", "command.dispatch",
                "messageId", "direct-message",
                "commandId", "direct-command",
                "tenantId", "tenant-a",
                "clientId", "client-a",
                "taskId", "task-1",
                "targetAgentId", "agent-1",
                "commandType", "TASK_INVITE");

        assertFalse(handler.sendDirectMessageToAgent("agent-1", command));
        verify(session, never()).sendMessage(any(TextMessage.class));

        register(handler, session, "agent-1");
        org.mockito.Mockito.clearInvocations(session);
        assertTrue(handler.sendDirectMessageToAgent("agent-1", command));
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void oneMatchingSessionFailureDoesNotPreventOtherExactSessionFromReceivingRawBytes()
            throws Exception {
        WebSocketSession failing = session("failing", "tenant-a", "client-a", "agent-1");
        WebSocketSession succeeding = session("succeeding", "tenant-a", "client-a", "agent-1");
        AgentWebSocketHandler handler = handler();
        register(handler, failing, "agent-1");
        register(handler, succeeding, "agent-1");
        org.mockito.Mockito.clearInvocations(failing, succeeding);
        doThrow(new IllegalStateException("simulated partial websocket failure"))
                .when(failing).sendMessage(any(TextMessage.class));
        byte[] raw = wire("tenant-a", "client-a", "task-1", "agent-1");

        AgentRawCommandDispatchResult result = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", raw);

        assertEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
        assertEquals(2, result.matchingSessionCount());
        assertEquals(1, result.sentSessionCount());
        verify(failing).sendMessage(any(TextMessage.class));
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(succeeding).sendMessage(sent.capture());
        assertArrayEquals(raw, sent.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidEnvelopeIsRejectedBeforePresenceLookupAndMatchingSendFailureIsDistinctFromOffline()
            throws Exception {
        WebSocketSession exact = session("exact", "tenant-a", "client-a", "agent-1");
        AgentWebSocketHandler handler = handler();
        register(handler, exact, "agent-1");
        org.mockito.Mockito.clearInvocations(exact);
        byte[] conflicting = wire("tenant-b", "client-a", "task-1", "agent-1");

        AgentRawCommandDispatchResult rejected = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", conflicting);

        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED, rejected.status());
        verify(exact, never()).sendMessage(any(TextMessage.class));

        byte[] utf16 = ("\ufeff" + new String(
                wire("tenant-a", "client-a", "task-1", "agent-1"), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_16LE);
        AgentRawCommandDispatchResult invalidEncoding = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", utf16);
        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED, invalidEncoding.status());
        verify(exact, never()).sendMessage(any(TextMessage.class));

        doThrow(new IllegalStateException("simulated websocket failure"))
                .when(exact).sendMessage(any(TextMessage.class));
        AgentRawCommandDispatchResult failed = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1",
                wire("tenant-a", "client-a", "task-1", "agent-1"));
        assertEquals(AgentRawCommandDispatchResult.Status.SEND_FAILED, failed.status());
        assertEquals(1, failed.matchingSessionCount());

        AgentRawCommandDispatchResult offline = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-missing",
                wire("tenant-a", "client-a", "task-1", "agent-missing"));
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, offline.status());
    }

    private AgentWebSocketHandler handler() {
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class))).thenAnswer(invocation -> {
            AgentRegisterDTO request = invocation.getArgument(0);
            return new AgentRegisterResultDTO(request.getAgentId(), "token", "online");
        });
        return new AgentWebSocketHandler(
                chatClient, agentServiceProvider, chatMessageDao, eventBroker);
    }

    private void register(
            AgentWebSocketHandler handler, WebSocketSession session, String agentId) throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"" + agentId
                        + "\",\"name\":\"Agent\"}"));
    }

    private WebSocketSession session(
            String id, String tenantId, String clientId, String agentId) {
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>(Map.of(
                "agentId", agentId,
                "runtimeInstanceId", "runtime-1",
                "jiacn", tenantId,
                "clientId", clientId)));
        return session;
    }

    private byte[] wire(
            String tenantId, String clientId, String taskId, String targetAgentId) {
        return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\","
                + "\"tenantId\":\"" + tenantId + "\","
                + "\"clientId\":\"" + clientId + "\","
                + "\"taskId\":\"" + taskId + "\","
                + "\"targetAgentId\":\"" + targetAgentId + "\","
                + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,"
                + "\"expiresAt\":2000000,\"payload\":{\"instruction\":\"执行🔥\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
