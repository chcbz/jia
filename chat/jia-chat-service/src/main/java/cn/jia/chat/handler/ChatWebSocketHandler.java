package cn.jia.chat.handler;

import cn.jia.core.entity.ProtocolMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated, subject-isolated chat stream; no provider frames escape this boundary. */
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final TypeReference<Map<String,Object>> MESSAGE = new TypeReference<>() { };
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Map<String,WebSocketSession> sessions=new ConcurrentHashMap<>();
    private final Map<String,Disposable.Composite> streams=new ConcurrentHashMap<>();
    private final ChatClient chatClient;
    public ChatWebSocketHandler(ChatClient chatClient){this.chatClient=chatClient;}

    @Override protected void handleTextMessage(WebSocketSession session,TextMessage text) throws Exception {
        if(session.getPrincipal()==null||session.getPrincipal().getName()==null||!sessions.containsKey(session.getId())){
            session.close(CloseStatus.POLICY_VIOLATION);return;
        }
        ProtocolMessage<Object> request;
        try {
            request=ProtocolMessage.decode(JSON.readValue(text.getPayload(),MESSAGE));
            if(!request.messageType().equals("chat.message")||request.correlationId()!=null||!(request.data() instanceof Map<?,?> data)
                    ||!data.keySet().equals(Set.of("conversationId","content"))||!(data.get("conversationId") instanceof String id)||id.isBlank()
                    ||!(data.get("content") instanceof String content)||content.isBlank())throw new IllegalArgumentException("Invalid chat request");
        } catch(Exception invalid){send(session,ProtocolMessage.event(UUID.randomUUID().toString(),"protocol.error",UUID.randomUUID().toString(),Map.of("reasonCode","MESSAGE_INVALID")));return;}
        var data=(Map<?,?>)request.data();String id=(String)data.get("conversationId"),content=(String)data.get("content");
        // This transport does not load workspace/chat DB resources. Its model memory must
        // never be selected globally by an untrusted conversationId shared by another user.
        String memoryId="ws:"+UUID.nameUUIDFromBytes((session.getPrincipal().getName()+"\0"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var lifecycle=Flux.defer(()->{
            send(session,reply(request,"chat.stream.started","E0",Map.of("conversationId",id)));
            return chatClient.prompt(content).advisors(a->a.param(ChatMemory.CONVERSATION_ID,memoryId)).stream().content();
        }).doOnNext(chunk->send(session,reply(request,"chat.stream.delta","E0",Map.of("conversationId",id,"content",chunk))))
                .doOnComplete(()->send(session,reply(request,"chat.stream.completed","E0",Map.of("conversationId",id))))
                .doOnError(error->send(session,reply(request,"chat.stream.error","CHAT_STREAM_FAILED",Map.of("conversationId",id))));
        Disposable.Composite owned=streams.get(session.getId());
        if(owned!=null)owned.add(lifecycle.subscribeOn(Schedulers.boundedElastic()).subscribe(ignored->{},error->{}));
    }
    private ProtocolMessage<?> reply(ProtocolMessage<?> request,String type,String code,Object data){
        return ProtocolMessage.reply(request,type,UUID.randomUUID().toString(),code,code.equals("E0")?"ok":"Chat stream failed",data);
    }
    private void send(WebSocketSession session,ProtocolMessage<?> message){
        try {synchronized(session){if(session.isOpen())session.sendMessage(new TextMessage(JSON.writeValueAsString(message)));}}
        catch(Exception failure){throw new IllegalStateException("Chat transport failed",failure);}
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if(session.getPrincipal()==null||session.getPrincipal().getName()==null){session.close(CloseStatus.POLICY_VIOLATION);return;}
        sessions.put(session.getId(),session);streams.put(session.getId(),Disposables.composite());
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){
        sessions.remove(session.getId());var owned=streams.remove(session.getId());if(owned!=null)owned.dispose();
    }
    public void sendMessageToAll(String content){
        String id=UUID.randomUUID().toString();var message=ProtocolMessage.event(id,"chat.message",id,Map.of("content",content));
        sessions.values().forEach(session->send(session,message));
    }
}
