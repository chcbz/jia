package cn.jia.chat.handler;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.NativeProviderCredentialBindingLookup;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketNativeProviderCredentialBindingTest {
    @Test void onlyUniqueCurrentAuthenticatedSessionIsReadyAndDisconnectClearsIt() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);AgentWebSocketHandler handler=handler(auth);
        WebSocketSession session=session("one","runtime-a");bind(handler,session,NativeProviderCredentialBindingDeclaration.parse(
                NativeProviderCredentialBindingDeclarationTest.candidate()));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        assertEquals(NativeProviderCredentialBindingLookup.State.READY,handler.current(scope()).state());
        handler.afterConnectionClosed(session,CloseStatus.NORMAL);
        assertEquals(NativeProviderCredentialBindingLookup.State.OFFLINE,handler.current(scope()).state());
    }
    @Test void staleAuthEpochAndMultipleCurrentSessionsNeverBecomeAuthority() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);AgentWebSocketHandler handler=handler(auth);
        WebSocketSession first=session("first","runtime-a"),second=session("second","runtime-b");
        bind(handler,first,NativeProviderCredentialBindingDeclaration.parse(NativeProviderCredentialBindingDeclarationTest.candidate()));
        bind(handler,second,NativeProviderCredentialBindingDeclaration.parse(NativeProviderCredentialBindingDeclarationTest.candidate()));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        assertEquals(NativeProviderCredentialBindingLookup.State.AMBIGUOUS,handler.current(scope()).state());
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalArgumentException("rotated"));
        assertEquals(NativeProviderCredentialBindingLookup.State.OFFLINE,handler.current(scope()).state());
    }
    @Test void storageFailureIsUnavailableAndManagedKeyPresenceDoesNotCreateDeclaration() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);AgentWebSocketHandler handler=handler(auth);
        WebSocketSession session=session("one","runtime-a");session.getAttributes().put("managedApiKeyId","ordinary-key");
        bind(handler,session,NativeProviderCredentialBindingDeclaration.parse(null));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        assertEquals(NativeProviderCredentialBindingLookup.State.UNDECLARED,handler.current(scope()).state());
        doThrow(new IllegalStateException("store down")).when(auth).isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        assertThrows(NativeProviderCredentialBindingLookup.SourceUnavailable.class,()->handler.current(scope()));
    }
    private static NativeProviderCredentialBindingLookup.Scope scope(){return new NativeProviderCredentialBindingLookup.Scope("0","client-a","owner-a","agent-a");}
    private static AgentWebSocketHandler handler(AgentRuntimeAuthenticationService auth){AgentWebSocketHandler value=new AgentWebSocketHandler(
            mock(ChatClient.class),mock(ObjectProvider.class),mock(ChatMessageDao.class),new ChatConversationEventBroker());value.setRuntimeAuthentication(auth);return value;}
    private static WebSocketSession session(String id,String runtime){WebSocketSession value=mock(WebSocketSession.class);when(value.getId()).thenReturn(id);
        when(value.isOpen()).thenReturn(true);when(value.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of("tenantId","0","clientId","client-a",
                "jiacn","owner-a","agentId","agent-a","runtimeInstanceId",runtime)));return value;}
    @SuppressWarnings("unchecked") private static void bind(AgentWebSocketHandler handler,WebSocketSession session,
            NativeProviderCredentialBindingDeclaration declaration)throws Exception{
        ((Map<String,WebSocketSession>)field(handler,"sessions")).put(session.getId(),session);
        ((Map<String,Boolean>)field(handler,"sessionDurableStateHealthy")).put(session.getId(),true);
        ((Map<String,Set<String>>)field(handler,"sessionAgentIds")).computeIfAbsent(session.getId(),x->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,Set<String>>)field(handler,"successfullyRegisteredAgentIds")).computeIfAbsent(session.getId(),x->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,NativeProviderCredentialBindingDeclaration>)field(handler,"sessionNativeProviderCredentialBinding")).put(session.getId(),declaration);
    }
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
}
