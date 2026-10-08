package cn.jia.chat.handler;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketControlledImageV3ExecutionTest {
    @Test void currentSessionAndCredentialDeclarationComeFromSameAuthenticatedSocket() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        AgentWebSocketHandler handler=handler(auth);WebSocketSession session=session("one","runtime-a");
        bind(handler,session,parseV3(ControlledImageBountyExecutionV3DeclarationTest.candidate(true)),
                NativeProviderCredentialBindingDeclaration.parse(
                        NativeProviderCredentialBindingDeclarationTest.candidate()));
        when(auth.isCurrentBinding("one","0","client-a","owner-a","agent-a","runtime-a"))
                .thenReturn(true);

        var scope=new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.DeclarationScope(
                "0","client-a","owner-a","agent-a");
        var sessionOnly=handler.currentSession(scope);var declaration=handler.current(scope);
        assertEquals(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,
                sessionOnly.state());
        assertEquals("runtime-a",sessionOnly.runtimeInstanceId());
        assertEquals(Set.of("GENERATE_IMAGE","EDIT_IMAGE"),Set.copyOf(sessionOnly.operations()));
        assertEquals(sessionOnly.runtimeInstanceId(),declaration.runtimeInstanceId());
        assertEquals("CONTROLLED_IMAGE_HTTP_V1",declaration.providerLane());
        assertEquals("binding-a",declaration.bindingId());assertEquals(7L,declaration.bindingEpoch());
        assertEquals("model-a",declaration.modelId());assertEquals(16,declaration.maxInputItems());
        assertEquals(1,declaration.maxOutboundRequestAttempts());assertEquals(1,declaration.precallFenceVersion());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE,
                handler.currentSession(scope).state());
    }

    @Test void differentSocketsStaleBindingAndAmbiguityNeverCombineIntoReadyAuthority() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        AgentWebSocketHandler handler=handler(auth);
        WebSocketSession execution=session("execution","runtime-execution");
        WebSocketSession binding=session("binding","runtime-binding");
        bind(handler,execution,parseV3(ControlledImageBountyExecutionV3DeclarationTest.candidate(true)),
                NativeProviderCredentialBindingDeclaration.parse(null));
        bind(handler,binding,parseV3(null),NativeProviderCredentialBindingDeclaration.parse(
                NativeProviderCredentialBindingDeclarationTest.candidate()));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenReturn(true);
        var scope=new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.DeclarationScope(
                "0","client-a","owner-a","agent-a");
        assertNotEquals(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,
                handler.current(scope).state());

        bind(handler,binding,parseV3(ControlledImageBountyExecutionV3DeclarationTest.candidate(true)),
                NativeProviderCredentialBindingDeclaration.parse(
                        NativeProviderCredentialBindingDeclarationTest.candidate()));
        assertEquals(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.AMBIGUOUS,
                handler.currentSession(scope).state());
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalArgumentException("rotated"));
        assertEquals(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE,
                handler.current(scope).state());
    }

    private static AgentWebSocketHandler handler(AgentRuntimeAuthenticationService auth) {
        AgentWebSocketHandler value=new AgentWebSocketHandler(mock(ChatClient.class),mock(ObjectProvider.class),
                mock(ChatMessageDao.class),new ChatConversationEventBroker());
        value.setRuntimeAuthentication(auth);return value;
    }
    private static WebSocketSession session(String id,String runtime) {
        WebSocketSession value=mock(WebSocketSession.class);when(value.getId()).thenReturn(id);
        when(value.isOpen()).thenReturn(true);when(value.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of(
                "tenantId","0","clientId","client-a","jiacn","owner-a","agentId","agent-a",
                "runtimeInstanceId",runtime)));return value;
    }
    @SuppressWarnings("unchecked") private static void bind(AgentWebSocketHandler handler,
            WebSocketSession session,Object execution,NativeProviderCredentialBindingDeclaration binding)
            throws Exception {
        ((Map<String,WebSocketSession>)field(handler,"sessions")).put(session.getId(),session);
        ((Map<String,Set<String>>)field(handler,"sessionAgentIds")).computeIfAbsent(
                session.getId(),ignored->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,Set<String>>)field(handler,"successfullyRegisteredAgentIds")).computeIfAbsent(
                session.getId(),ignored->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,Object>)field(handler,"sessionControlledImageV3")).put(session.getId(),execution);
        ((Map<String,NativeProviderCredentialBindingDeclaration>)field(
                handler,"sessionNativeProviderCredentialBinding")).put(session.getId(),binding);
    }
    private static Object parseV3(Object raw) throws Exception {
        Class<?> type=Class.forName("cn.jia.chat.handler.AgentWebSocketHandler$ControlledImageV3Declaration");
        Method method=type.getDeclaredMethod("parse",Object.class);method.setAccessible(true);
        return method.invoke(null,raw);
    }
    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
}
