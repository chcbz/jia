package cn.jia.chat.handler;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.HallActionDispatchResult;
import cn.jia.chat.service.HallActionDispatcher;
import cn.jia.chat.service.HallActionIntent;
import cn.jia.chat.service.JuyitingAgentRelayResult;
import cn.jia.chat.service.JuyitingAgentRelayService;
import cn.jia.chat.service.JuyitingConversationScopeService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import reactor.core.publisher.Flux;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
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
    @Mock
    BuiltinHallAgentSupport builtinHallAgentSupport;


    @Test
    void handlesAgentRegisterAssignAndReport() throws Exception {
        stubAgentSession("session-001", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));

        AgentTaskDTO assigned = new AgentTaskDTO();
        assigned.setId("task-001");
        assigned.setTenantId("juyiting");
        assigned.setClientId("jia_client");
        assigned.setStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        assigned.setAssignedAgentId("agent-001");
        assigned.setAssignedAgentName("Wu Yong");
        when(agentService.assignTask(any(String.class), any(AgentTaskAssignDTO.class))).thenReturn(assigned);
        when(agentService.listTaskMemberAgentIds("juyiting", "jia_client", "task-001"))
                .thenReturn(List.of("agent-001"));

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
        assertTrue(messages.contains("\"supportedProtocolVersions\":[1]"));
        assertTrue(messages.contains("\"command.dispatch\""));
        assertTrue(messages.contains("\"agent.register\""));
        assertTrue(messages.contains("\"type\":\"pong\""));
        assertTrue(messages.contains("\"type\":\"agent_registered\""));
        assertTrue(messages.contains("\"type\":\"task.event\""));
        assertTrue(messages.contains("\"eventType\":\"task.assignment.accepted\""));
        assertFalse(messages.contains("\"type\":\"task_assigned\""));
        assertTrue(messages.contains("\"type\":\"task_reported\""));
        assertTrue(messages.contains("\"status\":\"running\""));
        verify(agentService).reportTask(any(String.class), any(AgentTaskReportDTO.class));
        assertSafeServerDownlinks(messageCaptor.getAllValues());
    }

    @Test
    void registerAbilitiesDistinguishMissingEmptyAndNull() throws Exception {
        stubAgentSession("session-register-abilities", "agent-001", "runtime-1");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO(
                        "agent-001", "token", AgentConstants.STATUS_ONLINE));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","agentId":"agent-001","runtimeInstanceId":"runtime-1"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","agentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "abilities":[]}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","agentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "abilities":null}
                """));

        ArgumentCaptor<AgentRegisterDTO> captor = ArgumentCaptor.forClass(AgentRegisterDTO.class);
        verify(agentService, org.mockito.Mockito.times(2)).register(captor.capture());
        assertEquals(null, captor.getAllValues().get(0).getAbilities());
        assertTrue(captor.getAllValues().get(1).getAbilities().isEmpty());
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(1)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream()
                .map(TextMessage::getPayload)
                .anyMatch(payload -> payload.contains("abilities must be an array")));
    }

    @Test
    void presenceAbilitiesDistinguishMissingEmptyAndNull() throws Exception {
        stubAgentSession("session-presence-abilities", "agent-001", "runtime-1");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        AgentRuntimeDTO updated = new AgentRuntimeDTO();
        updated.setAgentId("agent-001");
        updated.setStatus(AgentConstants.STATUS_ONLINE);
        when(agentService.updateStatus(any(String.class), any(AgentStatusDTO.class)))
                .thenReturn(updated);
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.presence","agentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "status":"online"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.presence","agentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "status":"online","abilities":[]}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.presence","agentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "status":"online","abilities":null}
                """));

        ArgumentCaptor<AgentStatusDTO> captor = ArgumentCaptor.forClass(AgentStatusDTO.class);
        verify(agentService, org.mockito.Mockito.times(2))
                .updateStatus(org.mockito.Mockito.eq("agent-001"), captor.capture());
        assertEquals(null, captor.getAllValues().get(0).getAbilities());
        assertTrue(captor.getAllValues().get(1).getAbilities().isEmpty());
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(1)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream()
                .map(TextMessage::getPayload)
                .anyMatch(payload -> payload.contains("abilities must be an array")));
    }

    @Test
    void presenceForwardsClientAbilitiesForRuntimeRefresh() throws Exception {
        stubAgentSession("session-001", "agent-001", "runtime-1");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        AgentRuntimeDTO updated = new AgentRuntimeDTO();
        updated.setAgentId("agent-001");
        updated.setStatus(AgentConstants.STATUS_ONLINE);
        updated.setAbilities(List.of("code-edit", "review"));
        when(agentService.updateStatus(any(String.class), any(AgentStatusDTO.class))).thenReturn(updated);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.presence","schemaVersion":1,"messageId":"presence-1",
                 "agentId":"agent-001","sourceAgentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "status":"online",
                 "abilities":["code-edit","review"]}
                """));

        ArgumentCaptor<AgentStatusDTO> captor = ArgumentCaptor.forClass(AgentStatusDTO.class);
        verify(agentService).updateStatus(org.mockito.Mockito.eq("agent-001"), captor.capture());
        assertEquals(List.of("code-edit", "review"), captor.getValue().getAbilities());
    }

    @Test
    void abilitiesMustBeAnArrayOfNonBlankStrings() throws Exception {
        stubAgentSession("session-invalid-abilities", "agent-001", "runtime-1");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","agentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "abilities":{"planning":true}}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.presence","agentId":"agent-001","runtimeInstanceId":"runtime-1",
                 "status":"online","abilities":["planning",null]}
                """));

        verify(agentService, never()).register(any(AgentRegisterDTO.class));
        verify(agentService, never()).updateStatus(any(), any(AgentStatusDTO.class));
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.times(2)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream()
                .map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("abilities must be an array"));
        assertTrue(messages.contains("abilities must contain non-blank strings"));
    }

    @Test
    void scopedStatusAndCapabilityBroadcastsDoNotCrossTenantOrClient() throws Exception {
        WebSocketSession matching = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossTenant = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossClient = org.mockito.Mockito.mock(WebSocketSession.class);
        stubAgentSession(matching, "session-matching", "agent-a", "tenant-a", "client-a", null);
        stubAgentSession(crossTenant, "session-cross-tenant", "agent-b", "tenant-b", "client-a", null);
        stubAgentSession(crossClient, "session-cross-client", "agent-c", "tenant-a", "client-b", null);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(matching);
        handler.afterConnectionEstablished(crossTenant);
        handler.afterConnectionEstablished(crossClient);
        org.mockito.Mockito.clearInvocations(matching, crossTenant, crossClient);

        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId("agent-a");
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        AgentCapabilityDTO capability = new AgentCapabilityDTO();
        capability.setAgentId("agent-a");
        capability.setAbilities(List.of("planning"));

        handler.publishAgentStatus("client-a", "tenant-a", runtime);
        handler.publishCapabilityIndex("client-a", "tenant-a", List.of(capability));

        verify(matching, org.mockito.Mockito.times(2)).sendMessage(any(TextMessage.class));
        verify(crossTenant, never()).sendMessage(any(TextMessage.class));
        verify(crossClient, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void legacyTaskAssignmentConfirmationUsesTrustedScopeForEveryMatchingSession() throws Exception {
        WebSocketSession firstSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession secondSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossTenantSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossClientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        stubAgentSession(firstSession, "session-legacy-first", "agent-legacy", "tenant-a", "client-a", null);
        stubAgentSession(secondSession, "session-legacy-second", "agent-legacy", "tenant-a", "client-a", null);
        stubAgentSession(crossTenantSession, "session-legacy-cross-tenant", "agent-legacy",
                "tenant-b", "client-a", null);
        stubAgentSession(crossClientSession, "session-legacy-cross-client", "agent-legacy",
                "tenant-a", "client-b", null);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-legacy", "token", AgentConstants.STATUS_ONLINE));

        AgentTaskDTO assigned = assignedTask("task-legacy", "tenant-a", "client-a", "agent-legacy");
        when(agentService.assignTask(any(String.class), any(AgentTaskAssignDTO.class))).thenReturn(assigned);
        when(agentService.listTaskMemberAgentIds("tenant-a", "client-a", "task-legacy"))
                .thenReturn(List.of("agent-legacy"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        for (WebSocketSession candidate : List.of(firstSession, secondSession,
                crossTenantSession, crossClientSession)) {
            handler.afterConnectionEstablished(candidate);
            handler.handleTextMessage(candidate, new TextMessage(
                    "{\"type\":\"agent.register\",\"agentId\":\"agent-legacy\",\"name\":\"Agent\"}"));
        }
        org.mockito.Mockito.clearInvocations(firstSession, secondSession, crossTenantSession, crossClientSession);

        handler.handleTextMessage(firstSession, new TextMessage("""
                {"type":"task.assign","requestId":"legacy-assign","taskId":"task-legacy",
                 "agentId":"agent-legacy","targetAgentId":"agent-malicious",
                 "tenantId":"tenant-malicious","clientId":"client-malicious"}
                """));

        ArgumentCaptor<TextMessage> firstCaptor = ArgumentCaptor.forClass(TextMessage.class);
        ArgumentCaptor<TextMessage> secondCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(firstSession, org.mockito.Mockito.times(1)).sendMessage(firstCaptor.capture());
        verify(secondSession, org.mockito.Mockito.times(1)).sendMessage(secondCaptor.capture());
        verify(crossTenantSession, never()).sendMessage(any(TextMessage.class));
        verify(crossClientSession, never()).sendMessage(any(TextMessage.class));

        Map<String, Object> firstEnvelope = findOutboundEnvelope(
                firstCaptor.getAllValues(), AgentProtocolConstants.TYPE_TASK_EVENT);
        Map<String, Object> secondEnvelope = findOutboundEnvelope(
                secondCaptor.getAllValues(), AgentProtocolConstants.TYPE_TASK_EVENT);
        for (Map<String, Object> envelope : List.of(firstEnvelope, secondEnvelope)) {
            assertEquals("tenant-a", envelope.get("tenantId"));
            assertEquals("client-a", envelope.get("clientId"));
            assertEquals("task-legacy", envelope.get("taskId"));
            assertEquals("agent-legacy", envelope.get("agentId"));
            assertEquals("agent-legacy", envelope.get("targetAgentId"));
            assertEquals("legacy-assign", envelope.get("causationId"));
            assertEquals("task.assignment.accepted", envelope.get("eventType"));
            AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                    new AgentProtocolMessageNormalizer().normalizeInbound(envelope);
            assertEquals(AgentProtocolConstants.TYPE_TASK_EVENT, normalized.canonicalType());
        }
        assertFalse(firstCaptor.getValue().getPayload().contains("malicious"));
        assertFalse(secondCaptor.getValue().getPayload().contains("malicious"));
    }

    @Test
    void legacyTaskAssignmentConfirmationFailsClosedForMissingOrMismatchedScope() throws Exception {
        stubAgentSession("session-legacy-invalid-scope", "agent-legacy");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-legacy", "token", AgentConstants.STATUS_ONLINE));
        AgentTaskDTO missingScope = assignedTask("task-missing-scope", null, "jia_client", "agent-legacy");
        AgentTaskDTO mismatchedScope = assignedTask("task-cross-scope", "other-tenant", "jia_client", "agent-legacy");
        when(agentService.assignTask(any(String.class), any(AgentTaskAssignDTO.class)))
                .thenReturn(missingScope, mismatchedScope);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-legacy\",\"name\":\"Agent\"}"));
        org.mockito.Mockito.clearInvocations(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"task.assign\",\"taskId\":\"task-missing-scope\",\"agentId\":\"agent-legacy\"}"));
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"task.assign\",\"taskId\":\"task-cross-scope\",\"agentId\":\"agent-legacy\"}"));

        verify(session, never()).sendMessage(any(TextMessage.class));
        verify(agentService, never()).listTaskMemberAgentIds(any(String.class), any(String.class), any(String.class));
    }

    @Test
    void legacyTaskAssignmentConfirmationFailsClosedForNonMember() throws Exception {
        stubAgentSession("session-legacy-non-member", "agent-legacy");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-legacy", "token", AgentConstants.STATUS_ONLINE));
        when(agentService.assignTask(any(String.class), any(AgentTaskAssignDTO.class)))
                .thenReturn(assignedTask("task-non-member", "juyiting", "jia_client", "agent-legacy"));
        when(agentService.listTaskMemberAgentIds("juyiting", "jia_client", "task-non-member"))
                .thenReturn(List.of("agent-other"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-legacy\",\"name\":\"Agent\"}"));
        org.mockito.Mockito.clearInvocations(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"task.assign\",\"taskId\":\"task-non-member\",\"agentId\":\"agent-legacy\"}"));

        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void legacyTaskAssignmentConfirmationContinuesAfterOneSessionFails() throws Exception {
        WebSocketSession firstSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession secondSession = org.mockito.Mockito.mock(WebSocketSession.class);
        stubAgentSession(firstSession, "session-legacy-first-candidate", "agent-legacy",
                "tenant-a", "client-a", null);
        stubAgentSession(secondSession, "session-legacy-second-candidate", "agent-legacy",
                "tenant-a", "client-a", null);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-legacy", "token", AgentConstants.STATUS_ONLINE));
        when(agentService.assignTask(any(String.class), any(AgentTaskAssignDTO.class)))
                .thenReturn(assignedTask("task-failure", "tenant-a", "client-a", "agent-legacy"));
        when(agentService.listTaskMemberAgentIds("tenant-a", "client-a", "task-failure"))
                .thenReturn(List.of("agent-legacy"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        for (WebSocketSession candidate : List.of(firstSession, secondSession)) {
            handler.afterConnectionEstablished(candidate);
            handler.handleTextMessage(candidate, new TextMessage(
                    "{\"type\":\"agent.register\",\"agentId\":\"agent-legacy\",\"name\":\"Agent\"}"));
        }
        org.mockito.Mockito.clearInvocations(firstSession, secondSession);
        java.util.concurrent.atomic.AtomicInteger sendAttempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<TextMessage> deliveredMessage =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.stubbing.Answer<Void> failFirstSendOnly = invocation -> {
            TextMessage outbound = invocation.getArgument(0);
            if (sendAttempts.getAndIncrement() == 0) {
                throw new RuntimeException("simulated assignment confirmation failure");
            }
            deliveredMessage.set(outbound);
            return null;
        };
        org.mockito.Mockito.doAnswer(failFirstSendOnly)
                .when(firstSession).sendMessage(any(TextMessage.class));
        org.mockito.Mockito.doAnswer(failFirstSendOnly)
                .when(secondSession).sendMessage(any(TextMessage.class));

        handler.handleTextMessage(firstSession, new TextMessage(
                "{\"type\":\"task.assign\",\"taskId\":\"task-failure\",\"agentId\":\"agent-legacy\"}"));

        verify(firstSession, org.mockito.Mockito.times(1)).sendMessage(any(TextMessage.class));
        verify(secondSession, org.mockito.Mockito.times(1)).sendMessage(any(TextMessage.class));
        assertEquals(2, sendAttempts.get());
        Map<String, Object> envelope = findOutboundEnvelope(
                List.of(deliveredMessage.get()), AgentProtocolConstants.TYPE_TASK_EVENT);
        assertEquals("task.assignment.accepted", envelope.get("eventType"));
        assertEquals("agent-legacy", envelope.get("targetAgentId"));
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

        long sentAt = 1_753_155_000_000L;
        boolean delivered = handler.sendDirectMessageToAgent("agent-001", Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "messageId", "chat-message-1",
                "messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                "conversationId", "1001",
                "conversationType", "juyiting",
                "content", "@Wu Yong please reply",
                "sentAt", sentAt,
                "timestamp", sentAt));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(3)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertTrue(delivered);
        assertTrue(messages.contains("\"type\":\"agent_direct_message\""));
        assertTrue(messages.contains("\"messageType\":\"chat.message\""));
        assertTrue(messages.contains("\"conversationId\":\"1001\""));
        assertTrue(messages.contains("@Wu Yong please reply"));
        assertTrue(!messages.contains("\"commandType\""));
        Map<String, Object> chatEnvelope = findOutboundEnvelope(
                messageCaptor.getAllValues(), AgentProtocolConstants.TYPE_CHAT_MESSAGE);
        assertEquals(chatEnvelope.get("sentAt"), chatEnvelope.get("timestamp"));
        AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                new AgentProtocolMessageNormalizer().normalizeInbound(chatEnvelope);
        assertEquals(AgentProtocolConstants.TYPE_CHAT_MESSAGE, normalized.canonicalType());
        assertSafeServerDownlinks(messageCaptor.getAllValues());
    }

    @Test
    void sendsDirectTaskMessageToEveryOpenMatchingSessionOnlyOnce() throws Exception {
        WebSocketSession firstSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession secondSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession closedSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossTenantSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossClientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        stubAgentSession(firstSession, "session-multi-1", "agent-multi", "tenant-a", "client-a", null);
        stubAgentSession(secondSession, "session-multi-2", "agent-multi", "tenant-a", "client-a", null);
        stubAgentSession(closedSession, "session-multi-closed", "agent-multi", "tenant-a", "client-a", null);
        stubAgentSession(crossTenantSession, "session-multi-cross-tenant", "agent-multi", "tenant-b", "client-a", null);
        stubAgentSession(crossClientSession, "session-multi-cross-client", "agent-multi", "tenant-a", "client-b", null);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-multi", "token", AgentConstants.STATUS_ONLINE));
        when(agentService.listTaskMemberAgentIds("tenant-a", "client-a", "task-multi"))
                .thenReturn(List.of("agent-multi"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        for (WebSocketSession candidate : List.of(firstSession, secondSession, closedSession,
                crossTenantSession, crossClientSession)) {
            handler.afterConnectionEstablished(candidate);
            handler.handleTextMessage(candidate, new TextMessage(
                    "{\"type\":\"agent.register\",\"agentId\":\"agent-multi\",\"name\":\"Agent\"}"));
        }
        org.mockito.Mockito.clearInvocations(firstSession, secondSession, closedSession,
                crossTenantSession, crossClientSession);
        when(closedSession.isOpen()).thenReturn(false);

        boolean delivered = handler.sendDirectMessageToAgent("agent-multi", Map.of(
                "schemaVersion", 1,
                "messageId", "task-message-multi",
                "messageType", AgentProtocolConstants.TYPE_TASK_EVENT,
                "tenantId", "tenant-a",
                "clientId", "client-a",
                "taskId", "task-multi",
                "targetAgentId", "agent-multi"));

        assertTrue(delivered);
        verify(firstSession, org.mockito.Mockito.times(1)).sendMessage(any(TextMessage.class));
        verify(secondSession, org.mockito.Mockito.times(1)).sendMessage(any(TextMessage.class));
        verify(closedSession, never()).sendMessage(any(TextMessage.class));
        verify(crossTenantSession, never()).sendMessage(any(TextMessage.class));
        verify(crossClientSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void directDeliveryContinuesAfterOneSessionSendFails() throws Exception {
        WebSocketSession failingSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession healthySession = org.mockito.Mockito.mock(WebSocketSession.class);
        stubAgentSession(failingSession, "session-failing", "agent-multi", "tenant-a", "client-a", null);
        stubAgentSession(healthySession, "session-healthy", "agent-multi", "tenant-a", "client-a", null);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-multi", "token", AgentConstants.STATUS_ONLINE));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        for (WebSocketSession candidate : List.of(failingSession, healthySession)) {
            handler.afterConnectionEstablished(candidate);
            handler.handleTextMessage(candidate, new TextMessage(
                    "{\"type\":\"agent.register\",\"agentId\":\"agent-multi\",\"name\":\"Agent\"}"));
        }
        org.mockito.Mockito.clearInvocations(failingSession, healthySession);
        org.mockito.Mockito.doThrow(new RuntimeException("simulated send failure"))
                .when(failingSession).sendMessage(any(TextMessage.class));

        boolean delivered = handler.sendDirectMessageToAgent("agent-multi", Map.of(
                "schemaVersion", 1,
                "messageId", "chat-message-multi",
                "messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                "content", "continue after failure"));

        assertTrue(delivered);
        verify(failingSession, org.mockito.Mockito.times(1)).sendMessage(any(TextMessage.class));
        verify(healthySession, org.mockito.Mockito.times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void nonTaskDirectOutboundOverwritesUntrustedAgentIdentity() throws Exception {
        stubAgentSession("session-identity-overwrite", "agent-trusted");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-trusted", "token", AgentConstants.STATUS_ONLINE));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-trusted\",\"name\":\"Agent\"}"));
        org.mockito.Mockito.clearInvocations(session);

        boolean delivered = handler.sendDirectMessageToAgent("agent-trusted", Map.of(
                "schemaVersion", 1,
                "messageId", "identity-overwrite",
                "messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                "agentId", "agent-malicious",
                "targetAgentId", "agent-malicious",
                "content", "trusted target wins"));

        assertTrue(delivered);
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        Map<String, Object> envelope = findOutboundEnvelope(
                messageCaptor.getAllValues(), AgentProtocolConstants.TYPE_CHAT_MESSAGE);
        assertEquals("agent-trusted", envelope.get("agentId"));
        assertEquals("agent-trusted", envelope.get("targetAgentId"));
        assertFalse(messageCaptor.getValue().getPayload().contains("agent-malicious"));
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
        intent.setTenantId("juyiting");
        intent.setClientId("jia_client");
        intent.setActionType("task_briefing");
        intent.setTaskId("task-001");
        intent.setTargetAgentIds(java.util.List.of("agent-001"));
        intent.setConversationType("juyiting");
        intent.setInstruction("Read the bounty task and report the next plan.");
        intent.setReason("Task assigned");
        intent.setAutonomyLevel("assist");
        intent.setRequiresApproval(false);
        when(agentService.listTaskMemberAgentIds("juyiting", "jia_client", "task-001"))
                .thenReturn(List.of("agent-001"));

        AgentActionDispatchResultDTO result = handler.publishAgentAction(intent);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(3)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertEquals("dispatched", result.getStatus());
        assertEquals("agent-001", result.getTargetAgentId());
        assertTrue(messages.contains("\"type\":\"agent_direct_message\""));
        assertTrue(messages.contains("\"messageType\":\"command.dispatch\""));
        assertTrue(messages.contains("\"commandId\":\"intent-task-1\""));
        assertTrue(messages.contains("\"commandType\":\"TASK_INVITE\""));
        assertTrue(messages.contains("\"conversationType\":\"juyiting\""));
        assertTrue(messages.contains("\"actionType\":\"task_briefing\""));
        assertTrue(messages.contains("\"taskId\":\"task-001\""));
        assertTrue(messages.contains("Read the bounty task and report the next plan."));
        Map<String, Object> commandEnvelope = findOutboundEnvelope(
                messageCaptor.getAllValues(), AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        assertEquals(commandEnvelope.get("messageId"), commandEnvelope.get("requestId"));
        AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                new AgentProtocolMessageNormalizer().normalizeInbound(commandEnvelope);
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, normalized.canonicalType());
        assertSafeServerDownlinks(messageCaptor.getAllValues());
    }

    @Test
    void commandDeliveryUsesHandshakeIdentityScopeAndTaskMembership() throws Exception {
        WebSocketSession targetSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession otherAgentSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossTenantSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession crossClientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        stubAgentSession(targetSession, "session-target", "agent-target", "tenant-a", "client-a", null);
        stubAgentSession(otherAgentSession, "session-other", "agent-other", "tenant-a", "client-a", null);
        stubAgentSession(crossTenantSession, "session-cross-tenant", "agent-target", "tenant-b", "client-a", null);
        stubAgentSession(crossClientSession, "session-cross-client", "agent-target", "tenant-a", "client-b", null);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class))).thenAnswer(invocation -> {
            AgentRegisterDTO request = invocation.getArgument(0);
            return new AgentRegisterResultDTO(request.getAgentId(), "token", AgentConstants.STATUS_ONLINE);
        });
        when(agentService.listTaskMemberAgentIds("tenant-a", "client-a", "task-001"))
                .thenReturn(List.of("agent-target"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        for (WebSocketSession candidate : List.of(
                targetSession, otherAgentSession, crossTenantSession, crossClientSession)) {
            handler.afterConnectionEstablished(candidate);
            handler.handleTextMessage(candidate, new TextMessage(
                    "{\"type\":\"agent.register\",\"agentId\":\""
                            + candidate.getAttributes().get("agentId") + "\",\"name\":\"Agent\"}"));
        }
        org.mockito.Mockito.clearInvocations(
                targetSession, otherAgentSession, crossTenantSession, crossClientSession);

        AgentActionIntentDTO intent = new AgentActionIntentDTO();
        intent.setIntentId("intent-001");
        intent.setTenantId("tenant-a");
        intent.setClientId("client-a");
        intent.setActorAgentId("agent-target");
        intent.setTaskId("task-001");
        intent.setActionType("task_briefing");
        intent.setInstruction("execute");

        AgentActionDispatchResultDTO result = handler.publishAgentAction(intent);

        assertEquals("dispatched", result.getStatus());
        ArgumentCaptor<TextMessage> targetMessage = ArgumentCaptor.forClass(TextMessage.class);
        verify(targetSession).sendMessage(targetMessage.capture());
        verify(otherAgentSession, never()).sendMessage(any(TextMessage.class));
        verify(crossTenantSession, never()).sendMessage(any(TextMessage.class));
        verify(crossClientSession, never()).sendMessage(any(TextMessage.class));
        Map<String, Object> envelope = findOutboundEnvelope(
                targetMessage.getAllValues(), AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        assertEquals("tenant-a", envelope.get("tenantId"));
        assertEquals("client-a", envelope.get("clientId"));
        assertEquals("task-001", envelope.get("taskId"));
        assertEquals("agent-target", envelope.get("targetAgentId"));
        assertEquals(envelope.get("messageId"), envelope.get("requestId"));
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                new AgentProtocolMessageNormalizer().normalizeInbound(envelope).canonicalType());
    }

    @Test
    void taskScopedDirectDeliveryFailsClosedForMissingConflictingOrMaliciousScope() throws Exception {
        stubAgentSession("session-fail-closed", "agent-target");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-target", "token", AgentConstants.STATUS_ONLINE));
        when(agentService.listTaskMemberAgentIds("juyiting", "other-client", "task-001"))
                .thenReturn(List.of("agent-target"));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-target\",\"name\":\"Agent\"}"));
        org.mockito.Mockito.clearInvocations(session);

        boolean missingScope = handler.sendDirectMessageToAgent("agent-target", Map.of(
                "schemaVersion", 1,
                "messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                "taskId", "task-001",
                "targetAgentId", "agent-target"));
        boolean missingTarget = handler.sendDirectMessageToAgent("agent-target", Map.of(
                "schemaVersion", 1,
                "messageType", AgentProtocolConstants.TYPE_TASK_EVENT,
                "tenantId", "juyiting",
                "clientId", "jia_client",
                "taskId", "task-001"));
        boolean nonStringScope = handler.sendDirectMessageToAgent("agent-target", Map.of(
                "schemaVersion", 1,
                "messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                "tenantId", 7,
                "clientId", "jia_client",
                "taskId", "task-001",
                "targetAgentId", "agent-target"));
        boolean crossClient = handler.sendDirectMessageToAgent("agent-target", Map.of(
                "schemaVersion", 1,
                "messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                "tenantId", "juyiting",
                "clientId", "other-client",
                "taskId", "task-001",
                "targetAgentId", "agent-target"));
        boolean maliciousTarget = handler.sendDirectMessageToAgent("agent-target", Map.of(
                "schemaVersion", 1,
                "messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                "tenantId", "juyiting",
                "clientId", "jia_client",
                "taskId", "task-001",
                "targetAgentId", "agent-other"));
        boolean nestedConflict = handler.sendDirectMessageToAgent("agent-target", Map.of(
                "schemaVersion", 1,
                "messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                "tenantId", "juyiting",
                "clientId", "jia_client",
                "taskId", "task-001",
                "targetAgentId", "agent-target",
                "payload", Map.of("targetAgentId", "agent-other")));

        assertFalse(missingScope);
        assertFalse(missingTarget);
        assertFalse(nonStringScope);
        assertFalse(crossClient);
        assertFalse(maliciousTarget);
        assertFalse(nestedConflict);
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendsCapabilityIndexToAgentSession() throws Exception {
        stubAgentSession("session-capability", "agent-wuyong");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);

        AgentCapabilityDTO capability = new AgentCapabilityDTO();
        capability.setAgentId("agent-wuyong");
        capability.setName("吴用");
        capability.setAbilities(List.of("planning", "analysis"));
        capability.setRoles(List.of("planner"));
        capability.setStatus(AgentConstants.STATUS_ONLINE);
        capability.setCollaborationHint("适合任务拆解、方案评审、风险判断和协同安排。");
        when(agentService.listCapabilities()).thenReturn(List.of(capability));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"capability.lookup","requestId":"cap-1","agentId":"agent-wuyong"}
                """));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(2)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertTrue(messages.contains("\"type\":\"agent_capability_index\""));
        assertTrue(messages.contains("\"agentId\":\"agent-wuyong\""));
        assertTrue(messages.contains("\"planning\""));
        assertTrue(messages.contains("\"planner\""));
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
        verify(agentService, never()).reportTask(any(String.class), any(AgentTaskReportDTO.class));
    }

    @Test
    void canonicalWorkResultDoesNotInvokeLegacyTaskReportHandler() throws Exception {
        stubAgentSession("session-result", "agent-001", "runtime-1");

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"work.result","messageId":"result-1","sourceAgentId":"agent-001","runtimeInstanceId":"runtime-1","taskId":"task-001","status":"completed"}
                """));

        verify(agentService, never()).reportTask(any(String.class), any(AgentTaskReportDTO.class));
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(2)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"type\":\"protocol_error\""));
        assertTrue(messages.contains("\"code\":\"PROTOCOL_HANDLER_NOT_AVAILABLE\""));
    }

    @Test
    void publishesTaskEventOnlyToScopedTaskMembers() throws Exception {
        WebSocketSession memberSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession nonMemberSession = org.mockito.Mockito.mock(WebSocketSession.class);
        stubAgentSession(memberSession, "session-member", "agent-member", "juyiting", "jia_client", null);
        stubAgentSession(nonMemberSession, "session-non-member", "agent-other", "juyiting", "jia_client", null);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class))).thenAnswer(invocation -> {
            AgentRegisterDTO request = invocation.getArgument(0);
            return new AgentRegisterResultDTO(request.getAgentId(), "token", AgentConstants.STATUS_ONLINE);
        });
        when(agentService.listTaskMemberAgentIds("juyiting", "jia_client", "task-001"))
                .thenReturn(List.of("agent-member"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(memberSession);
        handler.afterConnectionEstablished(nonMemberSession);
        handler.handleTextMessage(memberSession, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-member\",\"name\":\"Member\"}"));
        handler.handleTextMessage(nonMemberSession, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-other\",\"name\":\"Other\"}"));
        org.mockito.Mockito.clearInvocations(memberSession, nonMemberSession);

        AgentTaskDTO task = new AgentTaskDTO();
        task.setId("task-001");
        task.setTenantId("juyiting");
        task.setClientId("jia_client");
        task.setTitle("Protocol review");
        task.setStatus(AgentConstants.TASK_STATUS_RUNNING);
        handler.publishTaskEvent("task_updated", task);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(memberSession).sendMessage(messageCaptor.capture());
        verify(nonMemberSession, never()).sendMessage(any(TextMessage.class));
        Map<String, Object> event = findOutboundEnvelope(
                messageCaptor.getAllValues(), AgentProtocolConstants.TYPE_TASK_EVENT);
        assertEquals("juyiting", event.get("tenantId"));
        assertEquals("jia_client", event.get("clientId"));
        assertEquals("task-001", event.get("taskId"));
        assertEquals("agent-member", event.get("targetAgentId"));
        assertFalse(event.containsKey("commandType"));
        assertEquals(AgentProtocolConstants.TYPE_TASK_EVENT,
                new AgentProtocolMessageNormalizer().normalizeInbound(event).canonicalType());
        assertSafeServerDownlinks(messageCaptor.getAllValues());
    }

    @Test
    void directTaskEventsUseCanonicalNonExecutableOuterType() throws Exception {
        stubAgentSession("session-direct-event", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-001","name":"Wu Yong"}
                """));
        when(agentService.listTaskMemberAgentIds("juyiting", "jia_client", "task-001"))
                .thenReturn(List.of("agent-001"));

        boolean eventDelivered = handler.sendDirectMessageToAgent("agent-001", Map.of(
                "schemaVersion", 1,
                "messageId", "event-1",
                "messageType", AgentProtocolConstants.TYPE_TASK_EVENT,
                "tenantId", "juyiting",
                "clientId", "jia_client",
                "taskId", "task-001",
                "targetAgentId", "agent-001"));
        boolean unsafeAssignmentDelivered = handler.sendDirectMessageToAgent("agent-001", Map.of(
                "messageType", AgentProtocolConstants.TYPE_TASK_ASSIGN_LEGACY,
                "taskId", "task-001"));

        assertTrue(eventDelivered);
        assertFalse(unsafeAssignmentDelivered);
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(4)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"type\":\"task.event\""));
        assertFalse(messages.contains("\"type\":\"task_event\""));
        assertFalse(messages.contains("\"type\":\"task.assign\""));
        assertSafeServerDownlinks(messageCaptor.getAllValues());
    }

    @Test
    void requiresRuntimeInstanceIdOnProtocolV1Registration() throws Exception {
        stubAgentSession("session-runtime-required", "agent-001");
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"agent.register","sourceAgentId":"agent-001","name":"Wu Yong"}
                """));

        verify(agentService, never()).register(any(AgentRegisterDTO.class));
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(2)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"type\":\"protocol_error\""));
        assertTrue(messages.contains("\"code\":\"RUNTIME_INSTANCE_ID_REQUIRED\""));
    }

    @Test
    void rejectsMissingRuntimeAfterProtocolV1Registration() throws Exception {
        stubAgentSession("session-runtime-missing", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"agent.register","sourceAgentId":"agent-001","runtimeInstanceId":"runtime-1","name":"Wu Yong"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"chat.message","messageId":"message-1","sourceAgentId":"agent-001","conversationId":"1001","content":"missing runtime"}
                """));

        verify(chatMessageDao, never()).insert(any());
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(4)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"code\":\"RUNTIME_INSTANCE_ID_REQUIRED\""));
    }

    @Test
    void rejectsLateRuntimeBindingAfterLegacyRegistration() throws Exception {
        stubAgentSession("session-runtime-late", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","agentId":"agent-001","name":"Wu Yong"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"chat.message","messageId":"message-1","sourceAgentId":"agent-001","runtimeInstanceId":"runtime-late","conversationId":"1001","content":"late binding"}
                """));

        verify(chatMessageDao, never()).insert(any());
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(4)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"code\":\"RUNTIME_INSTANCE_ID_NOT_FIXED\""));
    }

    @Test
    void rejectsRegistrationRuntimeConflictWithAuthenticatedSession() throws Exception {
        stubAgentSession("session-runtime-auth", "agent-001", "runtime-auth");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"agent.register","sourceAgentId":"agent-001","runtimeInstanceId":"runtime-payload","name":"Wu Yong"}
                """));

        verify(agentService, never()).register(any(AgentRegisterDTO.class));
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(2)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"code\":\"RUNTIME_INSTANCE_ID_MISMATCH\""));
    }

    @Test
    void rejectsRuntimeInstanceChangeWithinOneSession() throws Exception {
        stubAgentSession("session-runtime", "agent-001");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "token-001", AgentConstants.STATUS_ONLINE));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"agent.register","messageId":"register-1","sourceAgentId":"agent-001","runtimeInstanceId":"runtime-1","name":"Wu Yong"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"schemaVersion":1,"type":"chat.message","messageId":"message-1","sourceAgentId":"agent-001","runtimeInstanceId":"runtime-2","conversationId":"1001","content":"must be rejected"}
                """));

        verify(chatMessageDao, never()).insert(any());
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(4)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
        assertTrue(messages.contains("\"code\":\"RUNTIME_INSTANCE_ID_MISMATCH\""));
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

    @Test
    void dispatcherDispatchProducesCanonicalCommandEnvelopeThroughWebSocket() throws Exception {
        EsContext commandContext = new EsContext();
        commandContext.setJiacn("juyiting");
        commandContext.setClientId("jia_client");
        EsContextHolder.setContext(commandContext);
        stubAgentSession("session-cmd-dispatch", "agent-wuyong");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-wuyong", "token-001", AgentConstants.STATUS_ONLINE));
        when(agentService.listTaskMemberAgentIds("juyiting", "jia_client", "task-001"))
                .thenReturn(List.of("agent-wuyong"));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-wuyong","name":"Wu Yong"}
                """));

        org.mockito.Mockito.clearInvocations(session);
        when(session.isOpen()).thenReturn(true);
        EsContextHolder.setContext(commandContext);

        HallActionDispatcher dispatcher = new HallActionDispatcher(handler);

        HallActionIntent intent = new HallActionIntent();
        intent.setIntentId("intent-dispatch-1");
        intent.setActionType("ask_help");
        intent.setActorAgentId("agent-wuyong");
        intent.setTargetAgentIds(List.of("agent-linchong"));
        intent.setConversationId("1001");
        intent.setTaskId("task-001");
        intent.setInstruction("请向林冲说明阻塞并请求替代方案");
        intent.setReason("接口依赖阻塞");

        HallActionDispatchResult result = dispatcher.dispatch(intent);

        assertEquals("dispatched", result.getStatus());

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(1)).sendMessage(messageCaptor.capture());

        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Object>> mapType = new TypeReference<>() { };
        Map<String, Object> commandEnvelope = null;
        for (TextMessage textMessage : messageCaptor.getAllValues()) {
            Map<String, Object> event = mapper.readValue(textMessage.getPayload(), mapType);
            if (AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(event.get("messageType"))) {
                commandEnvelope = event;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(commandEnvelope,
                "Should find a command.dispatch messageType in WebSocket output");

        assertEquals(commandEnvelope.get("messageId"), commandEnvelope.get("requestId"),
                "messageId and requestId must be equal in canonical command.dispatch");
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, commandEnvelope.get("messageType"));

        AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                new AgentProtocolMessageNormalizer().normalizeInbound(commandEnvelope);
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, normalized.canonicalType());
        assertTrue(normalized.executionTrigger());

        assertSafeServerDownlinks(messageCaptor.getAllValues());
    }

    @Test
    void relayServiceProducesCanonicalChatEnvelopeThroughWebSocket() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);

        stubAgentSession("session-chat-relay", "agent-wuyong");
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-wuyong", "token-001", AgentConstants.STATUS_ONLINE));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-wuyong","name":"Wu Yong"}
                """));

        org.mockito.Mockito.clearInvocations(session);
        when(session.isOpen()).thenReturn(true);

        JuyitingConversationScopeService scopeService = new JuyitingConversationScopeService(builtinHallAgentSupport);
        JuyitingAgentRelayService relayService = new JuyitingAgentRelayService(
                handler, chatConversationEventBroker, builtinHallAgentSupport, chatMessageDao, agentService, scopeService);

        ChatMessageDTO chatMessage = new ChatMessageDTO();
        chatMessage.setContent("请回报当前进度");
        chatMessage.setConversationType("juyiting");
        chatMessage.setConversationScopeType("public");
        chatMessage.setConversationScopeKey("public");
        chatMessage.setSenderType("user");
        chatMessage.setSenderName("测试用户");
        chatMessage.setTargetAgentIds(List.of("agent-wuyong"));

        when(chatConversationEventBroker.stream("1001"))
                .thenReturn(Flux.just("{\"type\":\"agent_message\",\"content\":\"ok\"}"));

        JuyitingAgentRelayResult relayResult = relayService.relay(chatMessage, "1001", () -> Flux.just("builtin"));
        List<String> events = relayResult.stream().collectList().block(Duration.ofSeconds(5));

        assertTrue(relayResult.attempted());

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(1)).sendMessage(messageCaptor.capture());

        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Object>> mapType = new TypeReference<>() { };
        Map<String, Object> chatEnvelope = null;
        for (TextMessage textMessage : messageCaptor.getAllValues()) {
            Map<String, Object> event = mapper.readValue(textMessage.getPayload(), mapType);
            if (AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(event.get("messageType"))) {
                chatEnvelope = event;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(chatEnvelope,
                "Should find a chat.message messageType in WebSocket output");

        assertEquals(chatEnvelope.get("sentAt"), chatEnvelope.get("timestamp"),
                "sentAt and timestamp must be equal in canonical chat.message");
        assertEquals(AgentProtocolConstants.TYPE_CHAT_MESSAGE, chatEnvelope.get("messageType"));
        assertFalse(chatEnvelope.containsKey("commandType"));

        AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                new AgentProtocolMessageNormalizer().normalizeInbound(chatEnvelope);
        assertEquals(AgentProtocolConstants.TYPE_CHAT_MESSAGE, normalized.canonicalType());
        assertFalse(normalized.executionTrigger());

        assertSafeServerDownlinks(messageCaptor.getAllValues());

        EsContextHolder.setContext(new EsContext());
    }


    private cn.jia.agent.entity.AgentRuntimeDTO runtime(String agentId, String name) {
        cn.jia.agent.entity.AgentRuntimeDTO dto = new cn.jia.agent.entity.AgentRuntimeDTO();
        dto.setAgentId(agentId);
        dto.setName(name);
        dto.setStatus(AgentConstants.STATUS_ONLINE);
        return dto;
    }

    private AgentTaskDTO assignedTask(String taskId, String tenantId, String clientId, String agentId) {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId(taskId);
        task.setTenantId(tenantId);
        task.setClientId(clientId);
        task.setStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        task.setAssignedAgentId(agentId);
        task.setAssignedAgentName("Agent");
        return task;
    }

    private void stubAgentSession(String sessionId, String agentId) {
        stubAgentSession(sessionId, agentId, null);
    }

    private void stubAgentSession(String sessionId, String agentId, String runtimeInstanceId) {
        stubAgentSession(session, sessionId, agentId, "juyiting", "jia_client", runtimeInstanceId);
    }

    private void stubAgentSession(WebSocketSession targetSession, String sessionId, String agentId,
            String tenantId, String clientId, String runtimeInstanceId) {
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("agentId", agentId);
        attributes.put("clientId", clientId);
        attributes.put("jiacn", tenantId);
        if (runtimeInstanceId != null) {
            attributes.put("runtimeInstanceId", runtimeInstanceId);
        }
        org.mockito.Mockito.lenient().when(targetSession.getId()).thenReturn(sessionId);
        org.mockito.Mockito.lenient().when(targetSession.isOpen()).thenReturn(true);
        org.mockito.Mockito.lenient().when(targetSession.getAttributes()).thenReturn(attributes);
    }

    private Map<String, Object> findOutboundEnvelope(List<TextMessage> messages, String messageType)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Object>> mapType = new TypeReference<>() { };
        for (TextMessage textMessage : messages) {
            Map<String, Object> event = mapper.readValue(textMessage.getPayload(), mapType);
            if (messageType.equals(event.get("messageType"))) {
                return event;
            }
        }
        throw new AssertionError("Missing outbound Envelope for messageType=" + messageType);
    }

    private void assertSafeServerDownlinks(List<TextMessage> messages) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Object>> mapType = new TypeReference<>() { };
        Set<String> legacyExecutionOuterTypes = Set.of("codex.exec", "task.assign", "task_assigned", "task_event");
        for (TextMessage textMessage : messages) {
            Map<String, Object> event = mapper.readValue(textMessage.getPayload(), mapType);
            String outerType = String.valueOf(event.get("type"));
            assertFalse(legacyExecutionOuterTypes.contains(outerType),
                    () -> "unsafe legacy execution outer type: " + outerType);
            if (AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE.equals(outerType)) {
                String messageType = String.valueOf(event.get("messageType"));
                assertTrue(AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(messageType)
                                || AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(messageType),
                        () -> "agent_direct_message must be explicitly chat or command: " + messageType);
            }
        }
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
