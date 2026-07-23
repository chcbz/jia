package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.chat.handler.AgentProtocolMessageNormalizer;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HallActionDispatcherTest extends BaseMockTest {
    @Mock
    AgentWebSocketHandler agentWebSocketHandler;

    @AfterEach
    void tearDown() {
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void dispatchesOnlineIntentToAgentDirectMessage() {
        setScope("tenant-a", "client-a");
        when(agentWebSocketHandler.isAgentConnected("tenant-a", "client-a", "agent-linchong")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(eq("agent-linchong"), any(Map.class))).thenReturn(true);
        HallActionDispatcher dispatcher = new HallActionDispatcher(agentWebSocketHandler);

        HallActionIntent intent = new HallActionIntent();
        intent.setIntentId("intent-1");
        intent.setActionType("ask_help");
        intent.setActorAgentId("agent-linchong");
        intent.setTargetAgentIds(List.of("agent-wuyong"));
        intent.setConversationId("1001");
        intent.setTaskId("task-1");
        intent.setInstruction("请向吴用说明阻塞并请求替代方案");
        intent.setReason("接口依赖阻塞");
        intent.setContext(Map.of("taskTitle", "接口联调"));

        HallActionDispatchResult result = dispatcher.dispatch(intent);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentWebSocketHandler).sendDirectMessageToAgent(eq("agent-linchong"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("dispatched", result.getStatus());
        assertEquals(AgentProtocolConstants.LEGACY_AGENT_ACTION, payload.get("type"));
        assertEquals(AgentProtocolConstants.VERSION_1, payload.get("schemaVersion"));
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, payload.get("messageType"));
        assertEquals("intent-1", payload.get("commandId"));
        assertEquals(AgentProtocolConstants.COMMAND_REQUEST_RESPOND, payload.get("commandType"));
        assertEquals("agent-linchong", payload.get("targetAgentId"));
        assertEquals("tenant-a", payload.get("tenantId"));
        assertEquals("client-a", payload.get("clientId"));
        assertEquals("task-1", payload.get("taskId"));
        assertEquals(payload.get("messageId"), payload.get("requestId"));
        AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                new AgentProtocolMessageNormalizer().normalizeInbound(payload);
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, normalized.canonicalType());
        assertEquals("ask_help", payload.get("actionType"));
        assertEquals("请向吴用说明阻塞并请求替代方案", payload.get("content"));
        assertTrue(((Map<?, ?>) payload.get("metadata")).containsKey("reason"));
    }

    @Test
    void queuesOfflineIntentInMailbox() {
        setScope("tenant-a", "client-a");
        when(agentWebSocketHandler.isAgentConnected("tenant-a", "client-a", "agent-linchong")).thenReturn(false);
        HallActionDispatcher dispatcher = new HallActionDispatcher(agentWebSocketHandler);

        HallActionIntent intent = new HallActionIntent();
        intent.setIntentId("intent-2");
        intent.setActionType("request_report");
        intent.setActorAgentId("agent-linchong");
        intent.setConversationId("1001");
        intent.setTaskId("task-2");
        intent.setInstruction("请回报当前进展");

        HallActionDispatchResult result = dispatcher.dispatch(intent);

        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(eq("agent-linchong"), any(Map.class));
        assertEquals("queued", result.getStatus());
        assertEquals(1, dispatcher.mailbox("agent-linchong").size());
        assertEquals("intent-2", dispatcher.mailbox("agent-linchong").getFirst().getIntentId());
    }
    @Test
    void rejectsCommandWhenTrustedScopeIsMissing() {
        HallActionDispatcher dispatcher = new HallActionDispatcher(agentWebSocketHandler);
        HallActionIntent intent = new HallActionIntent();
        intent.setIntentId("intent-missing-scope");
        intent.setActorAgentId("agent-linchong");
        intent.setTaskId("task-1");

        HallActionDispatchResult result = dispatcher.dispatch(intent);

        assertEquals("failed", result.getStatus());
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
    }

    private void setScope(String tenantId, String clientId) {
        EsContext context = new EsContext();
        context.setJiacn(tenantId);
        context.setClientId(clientId);
        EsContextHolder.setContext(context);
    }

}
