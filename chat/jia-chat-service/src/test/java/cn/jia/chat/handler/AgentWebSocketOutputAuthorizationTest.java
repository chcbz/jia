package cn.jia.chat.handler;

import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.dto.OutputAuthReceiptDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.core.util.JsonUtil;
import cn.jia.test.BaseMockTest;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketOutputAuthorizationTest extends BaseMockTest {
    @Mock ChatClient chatClient;
    @Mock AgentService agentService;
    @Mock ObjectProvider<AgentService> agentServiceProvider;
    @Mock ChatMessageDao chatMessageDao;
    @Mock ChatConversationEventBroker eventBroker;
    @Mock OutputRunAuthorizationService outputAuthorizationService;

    @Test
    void registeredSessionReceivesExactUnicastReceiptAndBearerAuthorizes() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession requester = session("requester", "agent-1");
        WebSocketSession sibling = session("sibling", "agent-1");
        register(handler, requester);
        register(handler, sibling);
        org.mockito.Mockito.clearInvocations(requester, sibling);
        String runId = "00000000000000000000000000000001";
        when(outputAuthorizationService.issueTicket(
                "owner", "client", "agent-1", "runtime-1", "auth-1", runId))
                .thenReturn(new OutputAuthReceiptDTO(
                        "auth-1", runId, "opaque-bearer-token-abcdefghijklmnopqrstuvwxyz",
                        "1900000000000", OutputConstants.R1_TICKET_OPERATIONS));
        when(outputAuthorizationService.authorizeTicket(
                "opaque-bearer-token-abcdefghijklmnopqrstuvwxyz",
                OutputConstants.OP_UPLOAD, false))
                .thenReturn(new OutputTicketAuthorization(
                        "owner", "client", runId, OutputConstants.SOURCE_TASK,
                        "task-1", "agent-1", "7", "runtime-1",
                        OutputConstants.R1_TICKET_OPERATIONS, 1_900_000_000_000L));

        handler.handleTextMessage(requester, new TextMessage("""
                {"schemaVersion":1,"messageType":"output.auth.request",
                 "messageId":"auth-1","runId":"00000000000000000000000000000001"}
                """));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(requester).sendMessage(sent.capture());
        verify(sibling, never()).sendMessage(any(TextMessage.class));
        JsonNode wire = JsonUtil.getMapper().readTree(sent.getValue().getPayload());
        assertEquals(Set.of("causationId", "runId", "token", "expiresAt", "operations"),
                JsonUtil.getMapper().convertValue(wire, Map.class).keySet());
        assertEquals("auth-1", wire.get("causationId").asText());
        assertEquals(runId, wire.get("runId").asText());
        assertTrue(wire.get("expiresAt").isTextual());
        String bearer = wire.get("token").asText();
        assertEquals("owner", outputAuthorizationService.authorizeTicket(
                bearer, OutputConstants.OP_UPLOAD, false).tenantId());
    }

    @Test
    void unregisteredSessionCannotRequestTicket() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession session = session("unregistered", "agent-1");
        handler.afterConnectionEstablished(session);
        org.mockito.Mockito.clearInvocations(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"messageType":"output.auth.request",
                 "messageId":"auth-1","runId":"00000000000000000000000000000001"}
                """));

        verify(outputAuthorizationService, never()).issueTicket(
                any(), any(), any(), any(), any(), any());
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertTrue(sent.getValue().getPayload().contains("AGENT_NOT_REGISTERED"));
    }

    private AgentWebSocketHandler handler() {
        org.mockito.Mockito.lenient().when(agentServiceProvider.getIfAvailable())
                .thenReturn(agentService);
        org.mockito.Mockito.lenient().when(agentService.register(any(AgentRegisterDTO.class)))
                .thenAnswer(invocation -> {
            AgentRegisterDTO request = invocation.getArgument(0);
            return new AgentRegisterResultDTO(request.getAgentId(), "registration-token", "online");
        });
        AgentWebSocketHandler handler = new AgentWebSocketHandler(
                chatClient, agentServiceProvider, chatMessageDao, eventBroker);
        ReflectionTestUtils.setField(
                handler, "outputRunAuthorizationService", outputAuthorizationService);
        return handler;
    }

    private void register(AgentWebSocketHandler handler, WebSocketSession session) throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-1\",\"name\":\"Agent\"}"));
    }

    private WebSocketSession session(String id, String agentId) {
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>(Map.of(
                "agentId", agentId,
                "runtimeInstanceId", "runtime-1",
                "jiacn", "owner",
                "clientId", "client")));
        return session;
    }
}
