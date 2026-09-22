package cn.jia.chat.handler;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.service.AgentExecutionReportService;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketExecutionReportTest {
    private final ChatClient chatClient = mock(ChatClient.class);
    private final AgentService agents = mock(AgentService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentService> agentProvider = mock(ObjectProvider.class);
    private final ChatMessageDao messages = mock(ChatMessageDao.class);
    private final ChatConversationEventBroker broker = mock(ChatConversationEventBroker.class);
    private final AgentExecutionReportService reports = mock(AgentExecutionReportService.class);
    private final WebSocketSession session = mock(WebSocketSession.class);
    private AgentWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("agentId", "agent-1");
        attributes.put("clientId", "client-1");
        attributes.put("jiacn", "owner-1");
        attributes.put("runtimeInstanceId", "runtime-1");
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        when(agentProvider.getIfAvailable()).thenReturn(agents);
        when(agents.register(any(AgentRegisterDTO.class))).thenReturn(
                new AgentRegisterResultDTO("agent-1", "token-1", AgentConstants.STATUS_ONLINE));
        when(agents.listCapabilities()).thenReturn(List.of());
        when(reports.accept(any(), any())).thenReturn(
                new AgentExecutionReportService.ReportReceipt("report-1", "rr_result", "7", false));
        handler = new AgentWebSocketHandler(chatClient, agentProvider, messages, broker);
        handler.setExecutionReportService(reports);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","agentId":"agent-1","runtimeInstanceId":"runtime-1"}
                """));
        clearInvocations(session, reports);
    }

    @Test
    void acceptsExactD03WireAndReturnsCompatiblePrivateReceipt() throws Exception {
        handler.handleTextMessage(session, new TextMessage(codexResult("agent-1", "runtime-1")));

        ArgumentCaptor<AgentExecutionReportService.RuntimeScope> scope =
                ArgumentCaptor.forClass(AgentExecutionReportService.RuntimeScope.class);
        ArgumentCaptor<AgentExecutionReportService.ReportCommand> command =
                ArgumentCaptor.forClass(AgentExecutionReportService.ReportCommand.class);
        verify(reports).accept(scope.capture(), command.capture());
        assertEquals("0", scope.getValue().tenantId());
        assertEquals("owner-1", scope.getValue().ownerJiacn());
        assertEquals("agent-1", scope.getValue().agentId());
        assertEquals("report-1", command.getValue().reportId());
        assertEquals("dispatch-1", command.getValue().dispatchMessageId());
        assertEquals("CODEX_EXECUTION_RESULT", command.getValue().payload().get("resultType"));

        Map<String, Object> receipt = lastOutbound();
        assertEquals("work.result.receipt", receipt.get("messageType"));
        assertEquals("CODEX_EXECUTION_RESULT", receipt.get("resultType"));
        assertEquals("ACCEPTED", receipt.get("receiptStatus"));
        assertEquals("report-1", receipt.get("correlationId"));
        assertEquals("command-1", receipt.get("commandId"));
        assertEquals("agent-1", receipt.get("targetAgentId"));
        assertEquals("7", receipt.get("committedVersion"));
    }

    @Test
    void rejectsSpoofedIdentityAndRuntimeBeforeServiceWithoutEchoingSensitiveFields() throws Exception {
        handler.handleTextMessage(session, new TextMessage(codexResult("agent-other", "runtime-1")));
        verify(reports, never()).accept(any(), any());
        Map<String, Object> error = lastOutbound();
        assertEquals("REPORT_INVALID", error.get("code"));
        assertEquals("private, no-store", error.get("cacheControl"));
        assertEquals("report-1", error.get("correlationId"));
        assertFalse(error.containsKey("commandId"));
        assertFalse(error.containsKey("runtimeInstanceId"));
        assertFalse(error.containsKey("status"));

        clearInvocations(session, reports);
        handler.handleTextMessage(session, new TextMessage(codexResult("agent-1", "runtime-other")));
        verify(reports, never()).accept(any(), any());

        clearInvocations(session, reports);
        handler.handleTextMessage(session, new TextMessage(
                codexResult("agent-1", "runtime-1").replace(
                        "\"sourceAgentId\":\"agent-1\",\"agentId\":\"agent-1\",",
                        "\"sourceAgentId\":\"agent-1\",")));
        verify(reports, never()).accept(any(), any());
        assertEquals("REPORT_INVALID", lastOutbound().get("code"));
    }

    @Test
    void routesAllFiveCanonicalReportsButNotLegacyTaskReport() throws Exception {
        for (String json : List.of(
                generic("work.progress", "report-p", "\"status\":\"RUNNING\",\"percent\":10"),
                generic("work.heartbeat", "report-h", "\"status\":\"RUNNING\""),
                generic("work.result", "report-r", "\"outcome\":\"SUCCEEDED\",\"exitCode\":0"),
                generic("help.request", "report-q", "\"reasonCode\":\"NEEDS_INPUT\""),
                generic("artifact.publish", "report-a",
                        "\"artifactId\":\"artifact-1\",\"artifactVersion\":\"1\","
                                + "\"stageRef\":\"stage-1\",\"sha256\":\"" + "a".repeat(64)
                                + "\",\"byteLength\":\"12\",\"contentType\":\"text/plain\""))) {
            handler.handleTextMessage(session, new TextMessage(json));
        }
        verify(reports, org.mockito.Mockito.times(5)).accept(any(), any());

        clearInvocations(reports);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"task.report","requestId":"legacy-1","taskId":"task-1",
                 "agentId":"agent-1","status":"running"}
                """));
        verify(reports, never()).accept(any(), any());
    }

    private String codexResult(String targetAgentId, String runtimeInstanceId) {
        return """
                {"schemaVersion":1,"messageType":"work.result","messageId":"report-1",
                 "resultType":"CODEX_EXECUTION_RESULT","sourceAgentId":"agent-1","agentId":"agent-1",
                 "targetAgentId":"%s","commandId":"command-1","correlationId":"dispatch-1",
                 "runtimeInstanceId":"%s","status":"SUCCEEDED","exitCode":0}
                """.formatted(targetAgentId, runtimeInstanceId);
    }

    private String generic(String type, String reportId, String business) {
        return "{" +
                "\"schemaVersion\":1,\"messageType\":\"" + type + "\","
                + "\"messageId\":\"" + reportId + "\",\"reportId\":\"" + reportId + "\","
                + "\"sourceAgentId\":\"agent-1\",\"targetAgentId\":\"agent-1\","
                + "\"runtimeInstanceId\":\"runtime-1\",\"commandId\":\"command-1\","
                + "\"correlationId\":\"dispatch-1\",\"executionRef\":\"execution-1\","
                + "\"grantRevision\":\"1\",\"attempt\":1,\"fencingToken\":\"1\","
                + "\"sequence\":\"1\",\"occurredAt\":\"1\"," + business + "}";
    }

    private Map<String, Object> lastOutbound() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        TextMessage last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        return new ObjectMapper().readValue(last.getPayload(), new TypeReference<>() { });
    }
}
