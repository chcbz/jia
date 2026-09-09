package cn.jia.chat.handler;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketOutputContextDispatchTest extends BaseMockTest {
    @Mock ChatClient chatClient;
    @Mock AgentService agentService;
    @Mock ObjectProvider<AgentService> agentServiceProvider;
    @Mock ChatMessageDao chatMessageDao;
    @Mock ChatConversationEventBroker eventBroker;
    @Mock OutputRunAuthorizationService outputAuthorizationService;

    @Test
    void rawCommandRedeliveryKeepsCommandAndTrustedRunContextStable() throws Exception {
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-1", "token", "online"));
        AgentWebSocketHandler handler = new AgentWebSocketHandler(
                chatClient, agentServiceProvider, chatMessageDao, eventBroker);
        WebSocketSession session = session();
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-1\",\"name\":\"Agent\"}"));
        org.mockito.Mockito.clearInvocations(session);
        ReflectionTestUtils.setField(
                handler, "outputRunAuthorizationService", outputAuthorizationService);
        String runId = "00000000000000000000000000000002";
        when(outputAuthorizationService.requireFreshDispatchRuntime(
                "owner", "client", "agent-1", runId)).thenReturn("runtime-1");
        byte[] raw = ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\","
                + "\"tenantId\":\"owner\",\"clientId\":\"client\","
                + "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\","
                + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,\"expiresAt\":2000000,"
                + "\"outputContext\":{\"schemaVersion\":1,\"runId\":\"" + runId + "\","
                + "\"source\":{\"type\":\"TASK\",\"id\":\"task-1\"},"
                + "\"maxFileBytes\":\"52428800\",\"maxBatchBytes\":\"209715200\","
                + "\"manifestRelativePath\":\"outputs/" + runId + "/manifest.json\","
                + "\"capabilities\":[\"output.http.v1\"]},"
                + "\"payload\":{\"instruction\":\"执行\"}}")
                .getBytes(StandardCharsets.UTF_8);

        AgentRawCommandDispatchResult first = handler.dispatchExactRawCommand(
                "owner", "client", "task-1", "agent-1", raw);
        AgentRawCommandDispatchResult second = handler.dispatchExactRawCommand(
                "owner", "client", "task-1", "agent-1", raw);

        assertEquals(AgentRawCommandDispatchResult.Status.SENT, first.status());
        assertEquals(AgentRawCommandDispatchResult.Status.SENT, second.status());
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(sent.capture());
        assertArrayEquals(raw, sent.getAllValues().get(0).getPayload()
                .getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(raw, sent.getAllValues().get(1).getPayload()
                .getBytes(StandardCharsets.UTF_8));
        verify(outputAuthorizationService, times(2)).requireFreshDispatchRuntime(
                "owner", "client", "agent-1", runId);
        verify(outputAuthorizationService, never()).createOrRecoverRun(any());
    }

    private WebSocketSession session() {
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>(Map.of(
                "agentId", "agent-1",
                "runtimeInstanceId", "runtime-1",
                "jiacn", "owner",
                "clientId", "client")));
        return session;
    }
}
