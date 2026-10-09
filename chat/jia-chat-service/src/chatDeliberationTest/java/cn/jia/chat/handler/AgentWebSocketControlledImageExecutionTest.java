package cn.jia.chat.handler;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.ControlledImageExecutionSessionLookup;
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

class AgentWebSocketControlledImageExecutionTest {
    @Test void exactSiblingAndBindingFromSameCurrentSessionProduceOneReadySnapshot() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        AgentWebSocketHandler handler=handler(auth);WebSocketSession session=session("one","runtime-a");
        bind(handler,session,ControlledImageBountyExecutionDeclaration.parse(
                        ControlledImageBountyExecutionDeclarationTest.candidate(true)),
                NativeProviderCredentialBindingDeclaration.parse(
                        NativeProviderCredentialBindingDeclarationTest.candidate()));
        when(auth.isCurrentBinding(eq("one"),eq("0"),eq("client-a"),eq("owner-a"),
                eq("agent-a"),eq("runtime-a"))).thenReturn(true);

        var snapshot=handler.current(scope());
        assertEquals(ControlledImageExecutionSessionLookup.State.READY,snapshot.state());
        assertEquals("runtime-a",snapshot.runtimeInstanceId());
        assertEquals("PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V2",snapshot.transport());
        assertEquals("CONTROLLED_IMAGE_HTTP_V1",snapshot.providerLane());
        assertEquals("binding-a",snapshot.bindingId());
        assertEquals(7L,snapshot.bindingEpoch());
        assertEquals("model-a",snapshot.modelId());
        assertEquals(16,snapshot.maxInputItems());
        assertEquals(1,snapshot.maxOutboundRequestAttempts());
        assertEquals(1,snapshot.precallFenceVersion());
        handler.afterConnectionClosed(session,CloseStatus.NORMAL);
        assertEquals(ControlledImageExecutionSessionLookup.State.OFFLINE,handler.current(scope()).state());
    }

    @Test void declarationsOnDifferentSessionsNeverCombineIntoAuthority() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        AgentWebSocketHandler handler=handler(auth);
        WebSocketSession execution=session("execution","runtime-execution");
        WebSocketSession binding=session("binding","runtime-binding");
        bind(handler,execution,ControlledImageBountyExecutionDeclaration.parse(
                        ControlledImageBountyExecutionDeclarationTest.candidate(true)),
                NativeProviderCredentialBindingDeclaration.parse(null));
        bind(handler,binding,ControlledImageBountyExecutionDeclaration.parse(null),
                NativeProviderCredentialBindingDeclaration.parse(
                        NativeProviderCredentialBindingDeclarationTest.candidate()));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenReturn(true);

        var snapshot=handler.current(scope());
        assertNotEquals(ControlledImageExecutionSessionLookup.State.READY,snapshot.state());
        assertNull(snapshot.runtimeInstanceId());assertNull(snapshot.bindingId());
    }

    @Test void staleAuthBindingAndAmbiguousCurrentSessionsFailClosed() throws Exception {
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        AgentWebSocketHandler handler=handler(auth);
        WebSocketSession first=session("first","runtime-a"),second=session("second","runtime-b");
        var execution=ControlledImageBountyExecutionDeclaration.parse(
                ControlledImageBountyExecutionDeclarationTest.candidate(true));
        var binding=NativeProviderCredentialBindingDeclaration.parse(
                NativeProviderCredentialBindingDeclarationTest.candidate());
        bind(handler,first,execution,binding);bind(handler,second,execution,binding);
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenReturn(true);
        assertEquals(ControlledImageExecutionSessionLookup.State.AMBIGUOUS,handler.current(scope()).state());
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalArgumentException("rotated"));
        assertEquals(ControlledImageExecutionSessionLookup.State.OFFLINE,handler.current(scope()).state());
    }

    private static ControlledImageExecutionSessionLookup.Scope scope() {
        return new ControlledImageExecutionSessionLookup.Scope("0","client-a","owner-a","agent-a");
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
    @SuppressWarnings("unchecked")
    private static void bind(AgentWebSocketHandler handler,WebSocketSession session,
            ControlledImageBountyExecutionDeclaration execution,
            NativeProviderCredentialBindingDeclaration binding) throws Exception {
        ((Map<String,WebSocketSession>)field(handler,"sessions")).put(session.getId(),session);
        ((Map<String,Set<String>>)field(handler,"sessionAgentIds")).computeIfAbsent(
                session.getId(),ignored->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,Set<String>>)field(handler,"successfullyRegisteredAgentIds")).computeIfAbsent(
                session.getId(),ignored->ConcurrentHashMap.newKeySet()).add("agent-a");
        ((Map<String,ControlledImageBountyExecutionDeclaration>)field(
                handler,"sessionControlledImageBountyExecution")).put(session.getId(),execution);
        ((Map<String,NativeProviderCredentialBindingDeclaration>)field(
                handler,"sessionNativeProviderCredentialBinding")).put(session.getId(),binding);
    }
    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
}
