package cn.jia.chat.service;

import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.test.BaseMockTest;
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

    @Test
    void dispatchesOnlineIntentToAgentDirectMessage() {
        when(agentWebSocketHandler.isAgentConnected("agent-linchong")).thenReturn(true);
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

        ArgumentCaptor<Map<String, ?>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentWebSocketHandler).sendDirectMessageToAgent(eq("agent-linchong"), payloadCaptor.capture());
        Map<String, ?> payload = payloadCaptor.getValue();
        assertEquals("dispatched", result.getStatus());
        assertEquals("agent.action", payload.get("type"));
        assertEquals("intent-1", payload.get("requestId"));
        assertEquals("ask_help", payload.get("actionType"));
        assertEquals("请向吴用说明阻塞并请求替代方案", payload.get("content"));
        assertTrue(((Map<?, ?>) payload.get("metadata")).containsKey("reason"));
    }

    @Test
    void queuesOfflineIntentInMailbox() {
        when(agentWebSocketHandler.isAgentConnected("agent-linchong")).thenReturn(false);
        HallActionDispatcher dispatcher = new HallActionDispatcher(agentWebSocketHandler);

        HallActionIntent intent = new HallActionIntent();
        intent.setIntentId("intent-2");
        intent.setActionType("request_report");
        intent.setActorAgentId("agent-linchong");
        intent.setConversationId("1001");
        intent.setInstruction("请回报当前进展");

        HallActionDispatchResult result = dispatcher.dispatch(intent);

        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(eq("agent-linchong"), any(Map.class));
        assertEquals("queued", result.getStatus());
        assertEquals(1, dispatcher.mailbox("agent-linchong").size());
        assertEquals("intent-2", dispatcher.mailbox("agent-linchong").getFirst().getIntentId());
    }
}
