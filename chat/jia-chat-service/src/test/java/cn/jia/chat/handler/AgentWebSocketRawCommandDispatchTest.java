package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
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
    void hallTaskInviteCompatibilityBytesRemainExactThroughRawDispatch()
            throws Exception {
        WebSocketSession exact = session(
                "task-invite-compat", "tenant-a", "client-a", "agent-1");
        AgentWebSocketHandler handler = handler();
        register(handler, exact, "agent-1");
        org.mockito.Mockito.clearInvocations(exact);
        String intentId = "intent-task-invite";
        String commandType = AgentProtocolConstants.COMMAND_TASK_INVITE;
        AgentCommandDraft draft = new AgentCommandDraft(
                1, AgentCommandCanonicalCodec.hallCommandId(
                        "tenant-a", "client-a", "task-1", "agent-1",
                        intentId, commandType),
                "task-1", intentId, "tenant-a", "client-a", "task-1",
                null, "agent-1", commandType, 1_000L, 3_601_000L,
                intentId, new AgentHallCommandPayload(
                        "task_briefing", "Read the task briefing", "juyiting",
                        null, null, null, "assist", false, null));
        byte[] raw = AgentCommandCanonicalCodec.wireBytes(draft, "message-task-invite");

        AgentRawCommandDispatchResult result = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", raw);

        assertEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(exact).sendMessage(sent.capture());
        assertArrayEquals(raw, sent.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
        String wire = sent.getValue().getPayload();
        assertTrue(wire.contains("\"type\":\"agent_direct_message\""));
        assertTrue(wire.contains("\"agentId\":\"agent-1\""));
        assertTrue(wire.contains("\"actionType\":\"task_briefing\""));
        assertTrue(wire.contains("\"content\":\"Read the task briefing\""));
        assertTrue(wire.contains("\"autonomyLevel\":\"assist\""));
        assertTrue(wire.contains("\"metadata\":{"));
    }

    @Test
    void existingD05HallTaskInviteBytesRemainExactWithoutCompatibilityWrapper()
            throws Exception {
        WebSocketSession exact = session(
                "task-invite-d05", "tenant-a", "client-a", "agent-1");
        AgentWebSocketHandler handler = handler();
        register(handler, exact, "agent-1");
        org.mockito.Mockito.clearInvocations(exact);
        byte[] raw = d05HallTaskInviteWire();

        AgentRawCommandDispatchResult result = handler.dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", raw);

        assertEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(exact).sendMessage(sent.capture());
        assertArrayEquals(raw, sent.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
        assertFalse(sent.getValue().getPayload().contains("\"type\""));
        assertTrue(sent.getValue().getPayload().contains("\"intentId\":\"intent-d05\""));
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

    @Test
    void skillDispatchRequiresExactDedicatedKeyAndCurrentRegistrationNotOwnerWidePresence() throws Exception {
        var exact=session("skill-exact","tenant-a","client-a","agent-1");
        var shared=session("skill-shared","tenant-a","client-a","agent-1");
        exact.getAttributes().put("managedApiKeyId","dedicated-key");shared.getAttributes().put("managedApiKeyId","shared-key");
        var handler=handler();register(handler,exact,"agent-1");register(handler,shared,"agent-1");
        org.mockito.Mockito.clearInvocations(exact,shared);
        byte[] hash=cn.jia.agent.skill.SkillMarketplaceService.sessionRegistrationHash("agent-1","token");
        assertTrue(handler.isManagedSkillSessionReady("tenant-a","client-a","agent-1","dedicated-key",hash));
        assertFalse(handler.isManagedSkillSessionReady("tenant-a","client-a","agent-1","dedicated-key",new byte[32]));
        var payload=new cn.jia.agent.entity.AgentSkillInstallPayload("order-1","install-1","version-1","repo-test","1.0.0","500","sha256:"+"a".repeat(64),"/internal/agent/skill-installations/install-1/package");
        var draft=new AgentCommandDraft(1,"cmd_skill_install-1","order-1","install-1","tenant-a","client-a","order-1",null,"agent-1","SKILL_INSTALL",1L,3600001L,payload);
        byte[] raw=AgentCommandCanonicalCodec.wireBytes(draft,"message-1",1);
        assertEquals(AgentRawCommandDispatchResult.Status.SENT,handler.dispatchManagedSkill("tenant-a","client-a","agent-1","dedicated-key",hash,raw).status());
        verify(exact).sendMessage(any(TextMessage.class));verify(shared,never()).sendMessage(any(TextMessage.class));
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

    private byte[] d05HallTaskInviteWire() {
        return """
                {"schemaVersion":1,"messageType":"command.dispatch",\
                "messageId":"message-d05-task-invite","commandId":"cmd-d05-task-invite",\
                "correlationId":"task-1","causationId":"intent-d05",\
                "tenantId":"tenant-a","clientId":"client-a","taskId":"task-1",\
                "workItemId":null,"targetAgentId":"agent-1","commandType":"TASK_INVITE",\
                "issuedAt":1000,"expiresAt":3601000,"intentId":"intent-d05","attempt":1,\
                "payload":{"actionType":"task_briefing","instruction":"Read the task briefing",\
                "conversationType":"juyiting","reason":null,"conversationId":null,\
                "triggerEventId":null,"autonomyLevel":"supervised",\
                "requiresApproval":false,"context":null}}
                """.replace("\\\n", "").strip().getBytes(StandardCharsets.UTF_8);
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
