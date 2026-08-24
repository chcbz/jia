package cn.jia.chat.handler;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketCommandRecoveryTest extends BaseMockTest {
    @Mock ChatClient chatClient;
    @Mock AgentService agentService;
    @Mock ObjectProvider<AgentService> agentServiceProvider;
    @Mock ChatMessageDao chatMessageDao;
    @Mock ChatConversationEventBroker eventBroker;
    @Mock WebSocketSession session;
    @Mock AgentCommandReconnectSignal reconnectSignal;
    @Mock AgentCommandAckService ackService;

    private AgentWebSocketHandler handler;

    @BeforeEach
    void setUpHandler() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("agentId", "agent-a");
        attributes.put("runtimeInstanceId", "runtime-a");
        attributes.put("jiacn", "tenant-a");
        attributes.put("clientId", "client-a");
        when(session.getId()).thenReturn("session-a");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        handler = new AgentWebSocketHandler(
                chatClient, agentServiceProvider, chatMessageDao, eventBroker,
                null, new AgentProtocolMessageNormalizer(), reconnectSignal, ackService);
    }

    @Test
    void reconnectSignalOccursOnlyAfterSuccessfulExactRegistration() throws Exception {
        handler.afterConnectionEstablished(session);
        verify(reconnectSignal, never()).signalReconnect(any());

        AgentRuntimeDTO presence = new AgentRuntimeDTO();
        presence.setAgentId("agent-a");
        presence.setStatus(AgentConstants.STATUS_ONLINE);
        when(agentService.updateStatus(any(), any())).thenReturn(presence);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"messageType":"agent.presence","messageId":"presence-1",
                 "agentId":"agent-a","sourceAgentId":"agent-a","runtimeInstanceId":"runtime-a",
                 "status":"online"}
                """));
        verify(reconnectSignal, never()).signalReconnect(any());

        when(agentService.register(any(AgentRegisterDTO.class))).thenReturn(
                new AgentRegisterResultDTO("agent-other", "token", AgentConstants.STATUS_ONLINE));
        handler.handleTextMessage(session, registerMessage());
        verify(reconnectSignal, never()).signalReconnect(any());

        when(agentService.register(any(AgentRegisterDTO.class))).thenReturn(
                new AgentRegisterResultDTO("agent-a", "token", AgentConstants.STATUS_ONLINE));
        when(reconnectSignal.signalReconnect(any())).thenReturn(true);
        handler.handleTextMessage(session, registerMessage());

        ArgumentCaptor<AgentCommandReconnectScope> scope =
                ArgumentCaptor.forClass(AgentCommandReconnectScope.class);
        verify(reconnectSignal).signalReconnect(scope.capture());
        assertEquals(new AgentCommandReconnectScope(
                "tenant-a", "client-a", "agent-a", "agent-a", "AGENT_RECONNECT"), scope.getValue());

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(reconnectSignal).signalReconnect(any());
    }

    @Test
    void ackRequiresSuccessfulRegistrationAndUsesAuthoritativeSessionScope() throws Exception {
        handler.handleTextMessage(session, ackMessage("ack-before", "dispatch-1", "RECEIVED"));
        verify(ackService, never()).acknowledge(any(), anyLong());

        when(agentService.register(any(AgentRegisterDTO.class))).thenReturn(
                new AgentRegisterResultDTO("agent-a", "token", AgentConstants.STATUS_ONLINE));
        handler.handleTextMessage(session, registerMessage());
        when(ackService.acknowledge(any(), anyLong())).thenReturn(
                new AgentCommandAckResult(AgentCommandAckResult.Kind.ADVANCED, "RECEIVED", 3));
        handler.handleTextMessage(session, ackMessage("ack-1", "dispatch-1", "RECEIVED"));

        ArgumentCaptor<AgentCommandAck> ack = ArgumentCaptor.forClass(AgentCommandAck.class);
        verify(ackService).acknowledge(ack.capture(), anyLong());
        assertEquals("tenant-a", ack.getValue().tenantId());
        assertEquals("client-a", ack.getValue().clientId());
        assertEquals("agent-a", ack.getValue().registeredAgentId());
        assertEquals("ack-1", ack.getValue().messageId());
        assertEquals("dispatch-1", ack.getValue().correlationId());
        assertEquals("cmd-1", ack.getValue().commandId());
        assertEquals("task-1", ack.getValue().taskId());
        assertEquals("RECEIVED", ack.getValue().ackStatus());
    }

    @Test
    void ackHiddenScopeConflictIsRejectedWithoutDurableCallOrExistenceLeak() throws Exception {
        when(agentService.register(any(AgentRegisterDTO.class))).thenReturn(
                new AgentRegisterResultDTO("agent-a", "token", AgentConstants.STATUS_ONLINE));
        handler.handleTextMessage(session, registerMessage());
        org.mockito.Mockito.clearInvocations(ackService, session);

        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"messageType":"command.ack","messageId":"ack-x",
                 "correlationId":"dispatch-1","commandId":"cmd-1","taskId":"task-1",
                 "agentId":"agent-a","sourceAgentId":"agent-a","runtimeInstanceId":"runtime-a",
                 "tenantId":"tenant-other","receiverAgentId":"agent-other",
                 "ackStatus":"RECEIVED","ackAt":1700000000000,
                 "payload":{"targetAgentId":"agent-other"}}
                """));
        verify(ackService, never()).acknowledge(any(), anyLong());
        ArgumentCaptor<TextMessage> outbound = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(outbound.capture());
        String combined = outbound.getAllValues().stream().map(TextMessage::getPayload)
                .reduce("", String::concat);
        assertTrue(combined.contains("COMMAND_ACK_REJECTED"));
        assertTrue(!combined.contains("tenant-other") && !combined.contains("agent-other"));
    }

    private TextMessage registerMessage() {
        return new TextMessage("""
                {"schemaVersion":1,"messageType":"agent.register","messageId":"register-1",
                 "agentId":"agent-a","sourceAgentId":"agent-a","runtimeInstanceId":"runtime-a"}
                """);
    }

    private TextMessage ackMessage(String messageId, String correlationId, String status) {
        return new TextMessage("""
                {"schemaVersion":1,"messageType":"command.ack","messageId":"%s",
                 "correlationId":"%s","commandId":"cmd-1","taskId":"task-1",
                 "agentId":"agent-a","sourceAgentId":"agent-a","runtimeInstanceId":"runtime-a",
                 "ackStatus":"%s","ackAt":1700000000000}
                """.formatted(messageId, correlationId, status));
    }
}
