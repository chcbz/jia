package cn.jia.chat.service;

import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputRunRequest;
import cn.jia.agent.output.dto.OutputContextDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JuyitingAgentRelayOutputContextTest extends BaseMockTest {
    @Mock AgentWebSocketHandler webSocketHandler;
    @Mock ChatConversationEventBroker eventBroker;
    @Mock BuiltinHallAgentSupport builtinSupport;
    @Mock ChatMessageDao chatMessageDao;
    @Mock AgentService agentService;
    @Mock OutputRunAuthorizationService outputAuthorizationService;

    @BeforeEach
    void setContext() {
        EsContext context = new EsContext();
        context.setJiacn("owner");
        context.setClientId("client");
        EsContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void connectedUnknownSendResultKeepsServerCreatedConversationRun() {
        String runId = "00000000000000000000000000000003";
        when(webSocketHandler.isAgentConnected("agent-1")).thenReturn(true);
        when(webSocketHandler.sendDirectMessageToAgent(
                eq("agent-1"), any(Map.class), eq("owner"), eq("client")))
                .thenReturn(false);
        when(eventBroker.stream("101")).thenReturn(Flux.never());
        when(outputAuthorizationService.createOrRecoverRun(any())).thenReturn(Optional.of(
                new OutputContextDTO(1, runId,
                        new OutputSourceDTO(OutputConstants.SOURCE_CONVERSATION, "101"),
                        "52428800", "209715200", "outputs/" + runId + "/manifest.json",
                        List.of(OutputConstants.CAPABILITY_HTTP_V1))));
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(
                request(), "101", Flux::empty);
        List<String> events = result.stream().collectList().block();

        assertEquals(true, result.delivered());
        assertTrue(events.getFirst().contains("\"delivered\":false"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).sendDirectMessageToAgent(
                eq("agent-1"), payload.capture(), eq("owner"), eq("client"));
        OutputContextDTO context = (OutputContextDTO) payload.getValue().get("outputContext");
        assertEquals(runId, context.runId());
        ArgumentCaptor<OutputRunRequest> request = ArgumentCaptor.forClass(OutputRunRequest.class);
        verify(outputAuthorizationService).createOrRecoverRun(request.capture());
        assertEquals(OutputConstants.SOURCE_CONVERSATION, request.getValue().sourceType());
        assertEquals("101", request.getValue().sourceId());
        assertEquals(payload.getValue().get("messageId"), request.getValue().originId());
    }

    @Test
    void definitelyOfflineConversationCreatesNoRun() {
        when(webSocketHandler.isAgentConnected("agent-1")).thenReturn(false);
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(
                request(), "101", Flux::empty);
        result.stream().collectList().block();

        verify(outputAuthorizationService, never()).createOrRecoverRun(any());
        verify(webSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(Map.class), any(), any());
    }

    private JuyitingAgentRelayService service() {
        JuyitingConversationScopeService scopeService =
                new JuyitingConversationScopeService(builtinSupport);
        JuyitingAgentRelayService service = new JuyitingAgentRelayService(
                webSocketHandler, eventBroker, builtinSupport,
                chatMessageDao, agentService, scopeService);
        ReflectionTestUtils.setField(
                service, "outputRunAuthorizationService", outputAuthorizationService);
        return service;
    }

    private ChatMessageDTO request() {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("请生成成果文件");
        request.setConversationType("juyiting");
        request.setConversationScopeType("public");
        request.setConversationScopeKey("public");
        request.setSenderType("user");
        request.setSenderName("用户");
        request.setTargetAgentIds(List.of("agent-1"));
        return request;
    }
}
