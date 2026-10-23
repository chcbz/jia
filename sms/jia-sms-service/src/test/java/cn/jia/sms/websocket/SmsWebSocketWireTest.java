package cn.jia.sms.websocket;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.context.EsContext;
import jakarta.websocket.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class SmsWebSocketWireTest {
    @Test void missingAuthenticationClosesWithoutJoiningRegistry() throws Exception {
        var session=mock(Session.class);when(session.getRequestParameterMap()).thenReturn(Map.of());
        var server=new WebSocketServer();int before=WebSocketServer.getOnlineCount();server.onOpen(session);
        verify(session).close(any(CloseReason.class));assertEquals(before,WebSocketServer.getOnlineCount());
    }
    @Test void authenticatedMessageHasCorrelatedReceiptAndOldOrDuplicateRootsFail() throws Exception {
        var session=mock(Session.class);var remote=mock(RemoteEndpoint.Basic.class);when(session.getBasicRemote()).thenReturn(remote);
        when(session.getRequestParameterMap()).thenReturn(Map.of("access_token",List.of("synthetic-token")));
        var context=new EsContext();context.setJiacn("ws-fixture-owner");var server=new WebSocketServer();
        try(var auth=mockStatic(EsSecurityHandler.class)){
            auth.when(()->EsSecurityHandler.currentContext("synthetic-token")).thenReturn(context);
            server.onOpen(session);assertNotNull(WebSocketServer.getCurrentWebSocket("ws-fixture-owner"));
            clearInvocations(remote);
            server.onMessage("{\"protocolVersion\":1,\"requestId\":\"r\",\"messageType\":\"sms.message\",\"messageId\":\"m\",\"data\":{\"content\":\"中文\"}}",session);
            var text=org.mockito.ArgumentCaptor.forClass(String.class);verify(remote).sendText(text.capture());
            var reply=JsonMapper.builder().build().readTree(text.getValue());assertEquals("sms.message.receipt",reply.get("messageType").asText());
            assertEquals("m",reply.get("correlationId").asText());assertEquals("中文",reply.get("data").get("content").asText());
            clearInvocations(remote);server.onMessage("{\"content\":\"old\"}",session);verify(remote).sendText(contains("sms.protocol.error"));
        } finally {server.onClose();}
        assertNull(WebSocketServer.getCurrentWebSocket("ws-fixture-owner"));
    }
}
