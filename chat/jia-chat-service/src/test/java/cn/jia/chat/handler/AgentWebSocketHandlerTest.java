package cn.jia.chat.handler;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketHandlerTest extends BaseMockTest {
    @Mock
    ChatClient chatClient;
    @Mock
    AgentService agentService;
    @Mock
    ObjectProvider<AgentService> agentServiceProvider;
    @Mock
    ChatMessageDao chatMessageDao;
    @Mock
    ChatConversationEventBroker chatConversationEventBroker;
    @Mock
    WebSocketSession session;

    @Test
    void handlesAgentRegisterAssignAndReport() throws Exception {
        stubAgentSession("session-001", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));

        AgentTaskDTO assigned = new AgentTaskDTO();
        assigned.setId("task-001");
        assigned.setStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        assigned.setAssignedAgentId("agent-001");
        assigned.setAssignedAgentName("Wu Yong");
        when(agentService.assignTask(any(String.class), any(AgentTaskAssignDTO.class))).thenReturn(assigned);

        AgentTaskDTO running = new AgentTaskDTO();
        running.setId("task-001");
        running.setStatus(AgentConstants.TASK_STATUS_RUNNING);
        running.setAssignedAgentId("agent-001");
        running.setAssignedAgentName("Wu Yong");
        when(agentService.reportTask(any(String.class), any(AgentTaskReportDTO.class))).thenReturn(running);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\",\"requestId\":\"ping-1\"}"));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-001","name":"Wu Yong","abilities":["planning","analysis"]}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"task.assign","requestId":"assign-1","taskId":"task-001","agentId":"agent-001","allowQueue":false}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"task.report","requestId":"report-1","taskId":"task-001","agentId":"agent-001","status":"running","currentTaskTitle":"Verify"}
                """));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(5)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertTrue(messages.contains("\"type\":\"connected\""));
        assertTrue(messages.contains("\"agent.register\""));
        assertTrue(messages.contains("\"type\":\"pong\""));
        assertTrue(messages.contains("\"type\":\"agent_registered\""));
        assertTrue(messages.contains("\"type\":\"task_assigned\""));
        assertTrue(messages.contains("\"type\":\"task_reported\""));
        assertTrue(messages.contains("\"status\":\"running\""));
    }

    @Test
    void supportsSpecChatTypeAliases() throws Exception {
        when(session.getId()).thenReturn("session-chat");
        when(session.isOpen()).thenReturn(true);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"chat.stop","requestId":"stop-1","conversationId":"juyi-1"}
                """));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(2)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"type\":\"stopped\""));
        assertTrue(messages.contains("\"conversationId\":\"juyi-1\""));
    }

    @Test
    void returnsAgentErrorCodeForControlMessageFailures() throws Exception {
        stubAgentSession("session-001", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.assignTask(any(String.class), any(AgentTaskAssignDTO.class)))
                .thenThrow(new TestAgentException(AgentErrorConstants.AGENT_OFFLINE, "Agent is offline"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"task.assign","requestId":"assign-1","taskId":"task-001","agentId":"agent-001"}
                """));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        String message = messageCaptor.getValue().getPayload();
        assertTrue(message.contains("\"type\":\"error\""));
        assertTrue(message.contains("\"code\":\"AGENT_OFFLINE\""));
        assertTrue(message.contains("\"message\":\"Agent is offline\""));
    }

    @Test
    void extractsConversationTypeFromPayload() throws Exception {
        when(session.getId()).thenReturn("session-juyi");
        when(session.isOpen()).thenReturn(true);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"ping","requestId":"ping-juyi","conversationType":"juyiting","conversationId":"juyi-1"}
                """));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(2)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertTrue(messages.contains("\"type\":\"connected\""));
        assertTrue(messages.contains("\"type\":\"pong\""));
        assertTrue(messages.contains("\"conversationType\":\"juyiting\""));
        assertTrue(messages.contains("\"requestId\":\"ping-juyi\""));
    }

    @Test
    void sendsDirectMessageToRegisteredAgentSession() throws Exception {
        stubAgentSession("session-001", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-001","name":"Wu Yong"}
                """));

        boolean delivered = handler.sendDirectMessageToAgent("agent-001", Map.of(
                "conversationId", "1001",
                "conversationType", "juyiting",
                "content", "@Wu Yong please reply"));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(3)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertTrue(delivered);
        assertTrue(messages.contains("\"type\":\"agent_direct_message\""));
        assertTrue(messages.contains("\"conversationId\":\"1001\""));
        assertTrue(messages.contains("@Wu Yong please reply"));
    }

    @Test
    void publishesAgentActionIntentToRegisteredAgentSession() throws Exception {
        stubAgentSession("session-action", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-001","name":"Wu Yong"}
                """));

        AgentActionIntentDTO intent = new AgentActionIntentDTO();
        intent.setIntentId("intent-task-1");
        intent.setActorAgentId("agent-001");
        intent.setActionType("task_briefing");
        intent.setTaskId("task-001");
        intent.setTargetAgentIds(java.util.List.of("agent-001"));
        intent.setConversationType("juyiting");
        intent.setInstruction("Read the bounty task and report the next plan.");
        intent.setReason("Task assigned");
        intent.setAutonomyLevel("assist");
        intent.setRequiresApproval(false);

        AgentActionDispatchResultDTO result = handler.publishAgentAction(intent);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(3)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertEquals("dispatched", result.getStatus());
        assertEquals("agent-001", result.getTargetAgentId());
        assertTrue(messages.contains("\"type\":\"agent_direct_message\""));
        assertTrue(messages.contains("\"messageType\":\"agent.action\""));
        assertTrue(messages.contains("\"conversationType\":\"juyiting\""));
        assertTrue(messages.contains("\"actionType\":\"task_briefing\""));
        assertTrue(messages.contains("\"taskId\":\"task-001\""));
        assertTrue(messages.contains("Read the bounty task and report the next plan."));
    }

    @Test
    void savesAgentMessageFromRegisteredSession() throws Exception {
        stubAgentSession("session-001", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-001","name":"Wu Yong"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.message","requestId":"reply-1","conversationId":"1001","conversationType":"juyiting","agentId":"agent-001","senderName":"Wu Yong","content":"Inspect the current state first."}
                """));

        verify(chatMessageDao).insert(argThat((ChatMessageEntity message) ->
                "1001".equals(message.getConversationId())
                        && "ASSISTANT".equals(message.getMessageType())
                        && "Inspect the current state first.".equals(message.getContent())
                        && "juyiting".equals(message.getConversationType())
                        && "agent".equals(message.getSenderType())
                        && "Wu Yong".equals(message.getSenderName())
                        && message.getMetadata().contains("\"agentId\":\"agent-001\"")));
        verify(chatConversationEventBroker).publish(org.mockito.Mockito.eq("1001"), argThat(event ->
                "agent-001".equals(event.get("agentId"))
                        && "Wu Yong".equals(event.get("senderName"))
                        && "Inspect the current state first.".equals(event.get("content"))));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(4)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertTrue(messages.contains("\"type\":\"agent_message_saved\""));
        assertTrue(messages.contains("\"type\":\"agent_message\""));
        assertTrue(messages.contains("\"conversationId\":\"1001\""));
        assertTrue(messages.contains("Inspect the current state first."));
    }

    @Test
    void rejectsDifferentAgentOnSharedSession() throws Exception {
        stubAgentSession("session-001", "agent-001");
        when(agentService.get("agent-001")).thenReturn(runtime("agent-001", "吴用"));
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenAnswer(invocation -> {
                    AgentRegisterDTO request = invocation.getArgument(0);
                    return new AgentRegisterResultDTO(request.getAgentId(), "token-" + request.getAgentId(),
                            AgentConstants.STATUS_ONLINE);
                });

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-001","name":"吴用"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-2","agentId":"agent-002","name":"林冲"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.message","requestId":"reply-2","conversationId":"2001","conversationType":"juyiting","agentId":"agent-002","senderName":"林冲","content":"请先盘点当前局势。"}
                """));

        boolean delivered = handler.sendDirectMessageToAgent("agent-001", Map.of(
                "conversationId", "2001",
                "conversationType", "juyiting",
                "content", "@吴用 请定夺"));

        Set<String> connectedAgentIds = handler.getConnectedAgents().stream()
                .map(agent -> agent.getAgentId())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(delivered);
        assertEquals(Set.of("agent-001"), connectedAgentIds);
        verify(chatMessageDao, org.mockito.Mockito.never()).insert(any());
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(4)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"code\":\"AGENT_ID_MISMATCH\""));
    }

    private cn.jia.agent.entity.AgentRuntimeDTO runtime(String agentId, String name) {
        cn.jia.agent.entity.AgentRuntimeDTO dto = new cn.jia.agent.entity.AgentRuntimeDTO();
        dto.setAgentId(agentId);
        dto.setName(name);
        dto.setStatus(AgentConstants.STATUS_ONLINE);
        return dto;
    }

    private void stubAgentSession(String sessionId, String agentId) {
        org.mockito.Mockito.lenient().when(session.getId()).thenReturn(sessionId);
        org.mockito.Mockito.lenient().when(session.isOpen()).thenReturn(true);
        org.mockito.Mockito.lenient().when(session.getAttributes()).thenReturn(new java.util.HashMap<>(Map.of(
                "agentId", agentId,
                "clientId", "jia_client",
                "jiacn", "juyiting")));
    }

    static class TestAgentException extends RuntimeException {
        private final String code;

        TestAgentException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
