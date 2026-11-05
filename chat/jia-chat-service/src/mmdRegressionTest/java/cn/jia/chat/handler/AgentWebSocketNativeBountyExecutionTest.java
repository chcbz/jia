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
    @SuppressWarnings("unchecked")
    void nativeWorkHintUsesOnlyCurrentNegotiatedSessionAndNeverDispatchesACommand() throws Exception {
        var auth=mock(AgentRuntimeAuthenticationService.class);var handler=handler(auth);
        var old=session("old","runtime-old");var current=session("current","runtime-current");
        bind(handler,old,NativeBountyExecutionDeclaration.parse(NativeBountyExecutionDeclarationTest.candidate(true)));
        bind(handler,current,NativeBountyExecutionDeclaration.parse(NativeBountyExecutionDeclarationTest.candidate(true)));
        ((Set<String>)field(handler,"nativeWorkNotificationSessions")).addAll(Set.of("old","current"));
        var target=new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client-a","agent-a");
        assertEquals(java.util.List.of(target),handler.nativeWorkTargets());
        org.mockito.Mockito.verifyNoInteractions(auth);
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(inv->"current".equals(inv.getArgument(0)));
        handler.notifyNativeWork(new cn.jia.agent.entity.NativeConversationWorkAvailable("other","client-a","agent-a"));
        handler.notifyNativeWork(target);
        var sent=org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        org.mockito.Mockito.verify(current).sendMessage(sent.capture());
        org.mockito.Mockito.verify(old,org.mockito.Mockito.never()).sendMessage(any());
        var wire=new tools.jackson.databind.ObjectMapper().readTree(sent.getValue().getPayload());
        var fixture=new tools.jackson.databind.ObjectMapper().readTree(new org.springframework.core.io.ClassPathResource("contracts/native-work-hint-v1.json").getInputStream());
        assertEquals(fixture.get("data"),wire.get("data"));
        assertEquals("runtime.work.available",wire.get("messageType").asString());
        assertEquals("NATIVE_CONVERSATION",wire.get("data").get("lane").asString());
        assertEquals("runtime-current",wire.get("data").get("runtimeInstanceId").asString());
        assertEquals("1",wire.get("data").get("sessionGeneration").asString());
        assertEquals(false,wire.get("data").has("commandId"));
        handler.afterConnectionClosed(current,CloseStatus.NORMAL);
        assertEquals(java.util.List.of(target),handler.nativeWorkTargets()); // only stale local subscription remains
        handler.notifyNativeWork(target);
        org.mockito.Mockito.verify(current).sendMessage(any()); // no second send
    }

    @Test @SuppressWarnings("unchecked")
    void successfulRegistrationAcknowledgesFileCapabilityIndependentlyAndReplacementCanRemoveIt() throws Exception {
        var auth=mock(AgentRuntimeAuthenticationService.class);stubFence(auth);
        var service=mock(AgentService.class);
        var provider=(ObjectProvider<AgentService>)mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.register(any())).thenReturn(new AgentRegisterResultDTO("agent-a","unused",AgentConstants.STATUS_ONLINE));
        when(auth.bind(anyString(),any(),any())).thenReturn(new AgentRuntimeAuthenticationService.Receipt(
                "native-runtime-v1","0","client-a","owner-a","agent-a","runtime-current","rti_"+"1".repeat(32),"host-a",1,true));
        var handler=new AgentWebSocketHandler(mock(ChatClient.class),provider,mock(ChatMessageDao.class),new ChatConversationEventBroker());
        handler.setRuntimeAuthentication(auth);
        var current=session("current","runtime-current");
        bind(handler,current,NativeBountyExecutionDeclaration.parse(NativeBountyExecutionDeclarationTest.candidate(true)));
        var registration=new java.util.HashMap<String,Object>();
        registration.put("schemaVersion",1);registration.put("messageId","register-file-1");registration.put("agentId","agent-a");
        registration.put("runtimeInstanceId","runtime-current");registration.put("installationId","rti_"+"1".repeat(32));
        registration.put("hostId","host-a");registration.put("sessionGeneration",1L);
        registration.put("durableStateHealthy",true);registration.put("readyCommandTypes",java.util.List.of());
        registration.put("nativeWorkNotifications",1);registration.put("workspaceFileWorkNotifications",1);
        registration.put("archiveJobDiscovery",1); // Client cannot enable a disabled server controller.
        var json=new tools.jackson.databind.ObjectMapper();
        handler.handleTextMessage(current,new TextMessage(json.writeValueAsString(cn.jia.agent.common.AgentMessageWire.event("agent.register",registration,null))));
        var sent=org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        org.mockito.Mockito.verify(current,org.mockito.Mockito.atLeastOnce()).sendMessage(sent.capture());
        var receipt=sent.getAllValues().stream().map(message->json.readTree(message.getPayload()))
                .filter(value->"agent.registered".equals(value.get("messageType").asString())).findFirst().orElseThrow();
        assertEquals("agent.registered",receipt.get("messageType").asString());
        assertEquals(1,receipt.get("data").get("workspaceFileWorkNotifications").asInt());
        var shared = json.readTree(new org.springframework.core.io.ClassPathResource("contracts/archive-discovery-registration-v1.json").getInputStream());
        assertEquals(shared.get("data").get("archiveJobDiscovery"),receipt.get("data").get("archiveJobDiscovery"));
        assertEquals(shared.get("messageType"),receipt.get("messageType"));
        assertEquals(shared.get("correlationId"),receipt.get("correlationId"));
        assertEquals(false,receipt.get("data").has("nativeWorkNotifications")); // conversation feature is off
        assertEquals(java.util.List.of(new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client-a","agent-a","WORKSPACE_FILE")),handler.nativeWorkTargets());
        registration.put("archiveJobDiscovery",1); // Client cannot re-enable retired execution.
        registration.remove("workspaceFileWorkNotifications");registration.put("messageId","register-file-2");
        handler.handleTextMessage(current,new TextMessage(json.writeValueAsString(cn.jia.agent.common.AgentMessageWire.event("agent.register",registration,null))));
        assertEquals(java.util.List.of(),handler.nativeWorkTargets());
        var enabled=org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        org.mockito.Mockito.verify(current,org.mockito.Mockito.atLeastOnce()).sendMessage(enabled.capture());
        var enabledReceipt=enabled.getAllValues().stream().map(message->json.readTree(message.getPayload()))
                .filter(value->"agent.registered".equals(value.get("messageType").asString()))
                .reduce((first,last)->last).orElseThrow();
        assertEquals(0,enabledReceipt.get("data").get("archiveJobDiscovery").asInt());
    }

    @Test @SuppressWarnings("unchecked")
    void fileLaneRequiresItsOwnSubscriptionAndUsesSameCurrentFence() throws Exception {
        var auth=mock(AgentRuntimeAuthenticationService.class);var handler=handler(auth);
        var current=session("current","runtime-current");
        bind(handler,current,NativeBountyExecutionDeclaration.parse(NativeBountyExecutionDeclarationTest.candidate(true)));
        ((Set<String>)field(handler,"nativeWorkNotificationSessions")).add("current");
        var file=new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client-a","agent-a","WORKSPACE_FILE");
        handler.notifyNativeWork(file);
        org.mockito.Mockito.verify(current,org.mockito.Mockito.never()).sendMessage(any());
        ((Set<String>)field(handler,"workspaceFileWorkNotificationSessions")).add("current");
        assertEquals(2,handler.nativeWorkTargets().size());
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        handler.notifyNativeWork(file);
        var sent=org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        org.mockito.Mockito.verify(current).sendMessage(sent.capture());
        var json=new tools.jackson.databind.ObjectMapper();
        var wire=json.readTree(sent.getValue().getPayload());
        var fixture=json.readTree(new org.springframework.core.io.ClassPathResource("contracts/workspace-file-work-hint-v1.json").getInputStream());
        assertEquals(fixture.get("data"),wire.get("data"));
        handler.afterConnectionClosed(current,CloseStatus.NORMAL);
        assertEquals(java.util.List.of(),handler.nativeWorkTargets());
    }

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
