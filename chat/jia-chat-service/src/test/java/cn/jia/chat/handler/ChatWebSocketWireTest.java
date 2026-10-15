package cn.jia.chat.handler;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.socket.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatWebSocketWireTest {
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void authenticatesAndRejectsOldRootsBeforeModelCall() throws Exception {
        var client=mock(ChatClient.class);var handler=new ChatWebSocketHandler(client);var session=mock(WebSocketSession.class);
        handler.afterConnectionEstablished(session);verify(session).close(CloseStatus.POLICY_VIOLATION);verifyNoInteractions(client);
        when(session.getId()).thenReturn("socket");when(session.getPrincipal()).thenReturn(()->"owner");when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);handler.handleTextMessage(session,new TextMessage("{\"conversationId\":\"1\",\"content\":\"old\"}"));
        verifyNoInteractions(client);handler.afterConnectionClosed(session,CloseStatus.NORMAL);
    }
    @Test void actualSocketSendsStructuredChineseAndCompletion() throws Exception { run(Flux.just("中文","\\quoted"),"chat.stream.completed",4); }
    @Test void failedStreamHasErrorButNeverSuccessfulCompletion() throws Exception { run(Flux.error(new IllegalStateException("private-provider-detail")),"chat.stream.error",2); }
    private void run(Flux<String> chunks,String terminal,int count) throws Exception {
        var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);
        when(client.prompt(anyString()).advisors(any(Consumer.class)).stream().content()).thenReturn(chunks);
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn("socket");when(session.isOpen()).thenReturn(true);when(session.getPrincipal()).thenReturn(()->"owner");
        var frames=new CopyOnWriteArrayList<String>();var ended=new CountDownLatch(1);
        doAnswer(call->{String text=((TextMessage)call.getArgument(0)).getPayload();frames.add(text);if(text.contains(terminal))ended.countDown();return null;}).when(session).sendMessage(any());
        var handler=new ChatWebSocketHandler(client);handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session,new TextMessage("{\"protocolVersion\":1,\"requestId\":\"r\",\"messageType\":\"chat.message\",\"messageId\":\"m\",\"data\":{\"conversationId\":\"1\",\"content\":\"question\"}}"));
        assertTrue(ended.await(5,TimeUnit.SECONDS));assertEquals(count,frames.size());
        for(String frame:frames){var root=json.readTree(frame);assertEquals(8,root.size());assertEquals("m",root.get("correlationId").asText());assertEquals("r",root.get("requestId").asText());assertFalse(frame.contains("private-provider-detail"));}
        assertEquals("chat.stream.started",json.readTree(frames.getFirst()).get("messageType").asText());
        if(terminal.endsWith("error"))assertTrue(frames.stream().noneMatch(frame->frame.contains("chat.stream.completed")));
        else assertEquals("中文",json.readTree(frames.get(1)).get("data").get("content").asText());
        handler.afterConnectionClosed(session,CloseStatus.NORMAL);
    }
}
