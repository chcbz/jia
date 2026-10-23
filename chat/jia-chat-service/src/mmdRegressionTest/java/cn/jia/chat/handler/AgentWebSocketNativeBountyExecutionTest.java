package cn.jia.chat.handler;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.NativeBountyExecutionSessionLookup;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWebSocketNativeBountyExecutionTest {
    @Test
    void exactCurrentAuthenticatedSessionIsReadyAndOldDisconnectCannotClearReplacement() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        stubFence(auth);
        AgentWebSocketHandler handler=handler(auth);
        WebSocketSession old=session("old","runtime-old"), current=session("current","runtime-current");
        bind(handler,old,NativeBountyExecutionDeclaration.parse(NativeBountyExecutionDeclarationTest.candidate(true)));
        bind(handler,current,NativeBountyExecutionDeclaration.parse(NativeBountyExecutionDeclarationTest.candidate(true)));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(inv -> "current".equals(inv.getArgument(0)));

        assertEquals(NativeBountyExecutionSessionLookup.State.READY,handler.current(scope()).state());
        handler.afterConnectionClosed(old, CloseStatus.NORMAL);
        assertEquals(NativeBountyExecutionSessionLookup.State.READY,handler.current(scope()).state());
    }


    @Test
    void duplicateDeclarationKeysAreRejectedBeforeRegistrationAuthority() throws Exception {
        AgentService agentService=mock(AgentService.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agentService);
        AgentWebSocketHandler handler=new AgentWebSocketHandler(mock(ChatClient.class),provider,
                mock(ChatMessageDao.class),new ChatConversationEventBroker());
        handler.setRuntimeAuthentication(mock(AgentRuntimeAuthenticationService.class));
        WebSocketSession session=session("duplicate","runtime-a");
        handler.afterConnectionEstablished(session);
        String declaration=new tools.jackson.databind.ObjectMapper().writeValueAsString(
                NativeBountyExecutionDeclarationTest.candidate(true));
        declaration=declaration.replace("\"enabled\":true",
                "\"enabled\":true,\"enabled\":true");
        handler.handleTextMessage(session,new TextMessage("{\"schemaVersion\":1,"
                +"\"type\":\"agent.register\",\"messageId\":\"reg-duplicate\","
                +"\"agentId\":\"agent-a\",\"runtimeInstanceId\":\"runtime-a\",\"installationId\":\"rti_11111111111111111111111111111111\",\"hostId\":\"host-a\",\"sessionGeneration\":1,\"durableStateHealthy\":true,"
                +"\"nativeBountyExecution\":"+declaration+"}"));
        org.mockito.Mockito.verifyNoInteractions(agentService);
        assertEquals(NativeBountyExecutionSessionLookup.State.OFFLINE,
                handler.current(scope()).state());
    }


    @Test
    void invalidCurrentBindingIsOfflineAndStorageFailureIsUnavailable() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        stubFence(auth);
        AgentWebSocketHandler handler=handler(auth);WebSocketSession session=session("one","runtime-one");
        bind(handler,session,NativeBountyExecutionDeclaration.parse(NativeBountyExecutionDeclarationTest.candidate(true)));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalArgumentException("revoked"));
        assertEquals(NativeBountyExecutionSessionLookup.State.OFFLINE,handler.current(scope()).state());
        // Replace the throwing revocation stub without invoking it here.
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(auth).isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        assertThrows(NativeBountyExecutionSessionLookup.SourceUnavailable.class,()->handler.current(scope()));
    }

    private static NativeBountyExecutionSessionLookup.Scope scope(){return new NativeBountyExecutionSessionLookup.Scope("0","client-a","owner-a","agent-a");}
    private static AgentWebSocketHandler handler(AgentRuntimeAuthenticationService auth){
        AgentWebSocketHandler value=new AgentWebSocketHandler(mock(ChatClient.class),mock(ObjectProvider.class),mock(ChatMessageDao.class),new ChatConversationEventBroker());
        value.setRuntimeAuthentication(auth);return value;
    }
    private static WebSocketSession session(String id,String runtime){
        WebSocketSession value=mock(WebSocketSession.class);when(value.getId()).thenReturn(id);when(value.isOpen()).thenReturn(true);
        when(value.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of("tenantId","0","clientId","client-a","jiacn","owner-a","agentId","agent-a","runtimeInstanceId",runtime)));
        var attrs=value.getAttributes();
        attrs.put(cn.jia.chat.config.AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE,
                new AgentRuntimeAuthenticationService.Proof(new cn.jia.agent.security.AgentRuntimeAuthentication.Scope(
                        "0","client-a","owner-a","agent-a",runtime),"rti_"+"1".repeat(32),"host-a",1,"a".repeat(64),1,0));
        return value;
    }
    private static void stubFence(AgentRuntimeAuthenticationService auth) {
        org.mockito.Mockito.lenient().when(auth.withFence(any(),org.mockito.ArgumentMatchers.anyBoolean(),any()))
                .thenAnswer(inv->((java.util.function.Supplier<?>)inv.getArgument(2)).get());
    }
    @SuppressWarnings("unchecked") private static void bind(AgentWebSocketHandler handler,WebSocketSession session,NativeBountyExecutionDeclaration declaration)throws Exception{
        ((Map<String,WebSocketSession>)field(handler,"sessions")).put(session.getId(),session);
        ((Map<String,Boolean>)field(handler,"sessionDurableStateHealthy")).put(session.getId(),true);
        ((Map<String,Set<String>>)field(handler,"sessionAgentIds")).computeIfAbsent(session.getId(),ignored->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,Set<String>>)field(handler,"successfullyRegisteredAgentIds")).computeIfAbsent(session.getId(),ignored->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,NativeBountyExecutionDeclaration>)field(handler,"sessionNativeBountyExecution")).put(session.getId(),declaration);
    }
    private static Object field(Object target,String name)throws Exception{Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target);}
}
