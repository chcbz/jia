package cn.jia.chat.handler;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.ChatDeliberationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketTypedDeliberationTest {






    @Test void incompleteOrNestedTypedSidecarsNeverReachTheFinalService() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        AgentWebSocketHandler handler=handler(provider);ChatConversationService conversations=mock(ChatConversationService.class);
        ChatDeliberationService deliberation=mock(ChatDeliberationService.class);handler.setChatConversationService(conversations);handler.setChatDeliberationService(deliberation);
        WebSocketSession session=session("malformed-final");bind(handler,session);
        String incomplete=baseWire()+",\"outcomeContractVersion\":1}";
        handler.handleTextMessage(session,new TextMessage(incomplete));
        String nested="{\"schemaVersion\":1,\"messageType\":\"chat.message\",\"payload\":"
                +baseWire().replaceFirst("^\\{", "{")
                +",\"outcomeContractVersion\":1,\"interactionOutcome\":{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"plain\",\"clarification\":null,\"proposal\":null}}}";
        handler.handleTextMessage(session,new TextMessage(nested));
        verifyNoInteractions(deliberation);
    }


    private static ChatTurnEntity turn(){return new ChatTurnEntity().setTurnId("turn");}
    private static String finalWire(String outcome){return baseWire().replace("\"content\":\"plain\"","\"content\":\"你好🌏\"")+",\"outcomeContractVersion\":1,\"interactionOutcome\":"+outcome+"}";}
    private static String baseWire(){return "{\"schemaVersion\":1,\"messageType\":\"chat.message\",\"messageId\":\"m1\",\"agentId\":\"agent-a\",\"sourceAgentId\":\"agent-a\",\"runtimeInstanceId\":\"runtime\",\"conversationId\":\"42\",\"conversationGeneration\":\"1\",\"requestId\":\"request\",\"turnId\":\"turn\",\"dispatchId\":\"dispatch\",\"contextSnapshotId\":\"snapshot\",\"contextHash\":\"sha256:"+"a".repeat(64)+"\",\"content\":\"plain\"";}
    private static AgentWebSocketHandler handler(ObjectProvider<AgentService> provider){
        var handler=new AgentWebSocketHandler(mock(ChatClient.class),provider,mock(ChatMessageDao.class),new ChatConversationEventBroker());
        var auth=mock(AgentRuntimeAuthenticationService.class);stubFence(auth);
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        handler.setRuntimeAuthentication(auth);return handler;
    }
    private static void stubFence(AgentRuntimeAuthenticationService auth) {
        lenient().when(auth.withFence(any(),anyBoolean(),any())).thenAnswer(inv->((java.util.function.Supplier<?>)inv.getArgument(2)).get());
    }
    private static WebSocketSession session(String id){WebSocketSession value=mock(WebSocketSession.class);when(value.getId()).thenReturn(id);when(value.isOpen()).thenReturn(true);when(value.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of("tenantId","0","jiacn","owner","clientId","client","agentId","agent-a","runtimeInstanceId","runtime")));
        value.getAttributes().put(cn.jia.chat.config.AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE,
                new AgentRuntimeAuthenticationService.Proof(new cn.jia.agent.security.AgentRuntimeAuthentication.Scope(
                        "0","client","owner","agent-a","runtime"),"rti_"+"1".repeat(32),"host-a",1,"a".repeat(64),1,0));
        return value;}
    @SuppressWarnings("unchecked") private static void bind(AgentWebSocketHandler handler,WebSocketSession session)throws Exception{((Map<String,WebSocketSession>)field(handler,"sessions")).put(session.getId(),session);((Map<String,Boolean>)field(handler,"sessionDurableStateHealthy")).put(session.getId(),true);((Map<String,Set<String>>)field(handler,"sessionAgentIds")).computeIfAbsent(session.getId(),x->ConcurrentHashMap.newKeySet()).add("agent-a");((Map<String,Set<String>>)field(handler,"successfullyRegisteredAgentIds")).computeIfAbsent(session.getId(),x->ConcurrentHashMap.newKeySet()).add("agent-a");}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
}
