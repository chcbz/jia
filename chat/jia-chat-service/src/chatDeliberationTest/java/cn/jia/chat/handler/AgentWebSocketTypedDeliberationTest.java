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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketTypedDeliberationTest {
    @Test void declarationActivatesOnlyAfterReceiptAndIsRemovedOnClose() throws Exception {
        AgentService agents=mock(AgentService.class);@SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agents);when(agents.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-a","1".repeat(32),AgentConstants.STATUS_ONLINE));
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        when(auth.bind(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any()))
                .thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1","0","client","owner","agent-a","runtime",true));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        TypedDeliberationSessionRegistry registry=new TypedDeliberationSessionRegistry();
        AgentWebSocketHandler handler=handler(provider);handler.setRuntimeAuthentication(auth);handler.setTypedDeliberationSessions(registry);
        WebSocketSession session=session("s1");handler.afterConnectionEstablished(session);
        String declaration=new tools.jackson.databind.ObjectMapper().writeValueAsString(TypedDeliberationDeclarationTest.declaration("READY"));
        handler.handleTextMessage(session,new TextMessage("{\"schemaVersion\":1,\"messageType\":\"agent.register\",\"messageId\":\"r1\",\"agentId\":\"agent-a\",\"runtimeInstanceId\":\"runtime\",\"typedDeliberation\":"+declaration+"}"));
        assertEquals("s1",registry.requireSingleReady(new TypedDeliberationSessionRegistry.Scope("0","owner","client"),"agent-a").sessionId());
        handler.afterConnectionClosed(session,CloseStatus.NORMAL);
        assertThrows(IllegalStateException.class,()->registry.requireSingleReady(new TypedDeliberationSessionRegistry.Scope("0","owner","client"),"agent-a"));
    }

    @Test void independentInspectionDeclarationActivatesOnlyAfterRegistrationAndClosesWithSocket() throws Exception {
        AgentService agents=mock(AgentService.class);@SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agents);when(agents.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-a","1".repeat(32),AgentConstants.STATUS_ONLINE));
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        when(auth.bind(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any()))
                .thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1","0","client","owner","agent-a","runtime",true));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        TypedInspectionSessionRegistry registry=new TypedInspectionSessionRegistry();
        AgentWebSocketHandler handler=handler(provider);handler.setRuntimeAuthentication(auth);handler.setTypedInspectionSessions(registry);
        WebSocketSession session=session("inspection-registration");handler.afterConnectionEstablished(session);
        String declaration=new tools.jackson.databind.ObjectMapper().writeValueAsString(
                TypedInspectionDeclarationTest.declaration(true));
        handler.handleTextMessage(session,new TextMessage("{\"schemaVersion\":1,\"messageType\":\"agent.register\",\"messageId\":\"r1\",\"agentId\":\"agent-a\",\"runtimeInstanceId\":\"runtime\",\"typedInspection\":"+declaration+"}"));
        assertEquals("inspection-registration",registry.requireSingleReady(
                new TypedInspectionSessionRegistry.Scope("0","owner","client"),"agent-a").sessionId());
        handler.afterConnectionClosed(session,CloseStatus.NORMAL);
        assertThrows(IllegalStateException.class,()->registry.requireSingleReady(
                new TypedInspectionSessionRegistry.Scope("0","owner","client"),"agent-a"));
    }

    @Test void malformedDeclarationCanRegisterTheAgentButNeverAdvertisesTypedReadiness() throws Exception {
        AgentService agents=mock(AgentService.class);@SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agents);when(agents.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-a","1".repeat(32),AgentConstants.STATUS_ONLINE));
        AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        when(auth.bind(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any()))
                .thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1","0","client","owner","agent-a","runtime",true));
        when(auth.isCurrentBinding(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        TypedDeliberationSessionRegistry registry=new TypedDeliberationSessionRegistry();
        AgentWebSocketHandler handler=handler(provider);handler.setRuntimeAuthentication(auth);handler.setTypedDeliberationSessions(registry);
        WebSocketSession session=session("unsupported");handler.afterConnectionEstablished(session);
        Map<String,Object> malformed=new java.util.LinkedHashMap<>(TypedDeliberationDeclarationTest.declaration("READY"));
        malformed.put("operations",java.util.List.of("GENERATE_IMAGE"));
        String declaration=new tools.jackson.databind.ObjectMapper().writeValueAsString(malformed);
        handler.handleTextMessage(session,new TextMessage("{\"schemaVersion\":1,\"messageType\":\"agent.register\",\"messageId\":\"r1\",\"agentId\":\"agent-a\",\"runtimeInstanceId\":\"runtime\",\"typedDeliberation\":"+declaration+"}"));
        assertThrows(IllegalStateException.class,()->registry.requireSingleReady(
                new TypedDeliberationSessionRegistry.Scope("0","owner","client"),"agent-a"));
        assertEquals(1,registry.size());
    }

    @Test void strictRawFinalCarriesExactTypedObjectToAtomicServiceAndRejectsDuplicates() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        AgentWebSocketHandler handler=handler(provider);ChatConversationService conversations=mock(ChatConversationService.class);
        ChatDeliberationService deliberation=mock(ChatDeliberationService.class);handler.setChatConversationService(conversations);handler.setChatDeliberationService(deliberation);
        WebSocketSession session=session("final");bind(handler,session);
        ChatConversationEntity conversation=new ChatConversationEntity().setId(42L).setConversationType("juyiting")
                .setTargetAgentIds("[\"agent-a\"]").setLifecycleGeneration(1L);conversation.setTenantId("0");conversation.setClientId("client");conversation.setJiacn("owner");
        when(conversations.getOwned("owner","client","42")).thenReturn(conversation);
        when(deliberation.persistFinal(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),any(),any()))
                .thenReturn(new ChatDeliberationService.FinalResult(ChatDeliberationService.FinalStatus.PERSISTED,9L,"8","evt",turn(),null));
        String outcome="{\"schemaVersion\":1.0,\"kind\":\"ANSWER\",\"text\":\"你好🌏\",\"clarification\":null,\"proposal\":null}";
        handler.handleTextMessage(session,new TextMessage(finalWire(outcome)));
        ArgumentCaptor<String> raw=ArgumentCaptor.forClass(String.class);
        verify(deliberation).persistFinal(eq("0"),eq("owner"),eq("client"),eq("42"),eq(1L),eq("agent-a"),eq("request"),eq("turn"),eq("dispatch"),eq("snapshot"),eq("sha256:"+"a".repeat(64)),eq("你好🌏"),eq(1),raw.capture(),isNull(),any());
        assertEquals(outcome,raw.getValue());
        ArgumentCaptor<TextMessage> receipt=ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(receipt.capture());
        assertTrue(receipt.getValue().getPayload().contains("\"type\":\"agent_message_saved\""));
        assertTrue(receipt.getValue().getPayload().contains("\"turnId\":\"turn\""));
        assertTrue(receipt.getValue().getPayload().contains("\"messageId\":\"9\""));
        assertTrue(receipt.getValue().getPayload().contains("\"eventId\":\"evt\""));
        assertTrue(receipt.getValue().getPayload().contains("\"duplicate\":false"));
        clearInvocations(deliberation);
        handler.handleTextMessage(session,new TextMessage(finalWire(outcome.replace("\"kind\":\"ANSWER\"","\"kind\":\"ANSWER\",\"kind\":\"CLARIFY\""))));
        verifyNoInteractions(deliberation);
    }

    @Test void v2FinalForwardsExactReceiptWithoutChangingV1Wire() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        AgentWebSocketHandler handler=handler(provider);ChatConversationService conversations=mock(ChatConversationService.class);
        ChatDeliberationService deliberation=mock(ChatDeliberationService.class);handler.setChatConversationService(conversations);handler.setChatDeliberationService(deliberation);
        WebSocketSession session=session("inspection-final");bind(handler,session);
        ChatConversationEntity conversation=new ChatConversationEntity().setId(42L).setConversationType("juyiting")
                .setTargetAgentIds("[\"agent-a\"]").setLifecycleGeneration(1L);conversation.setTenantId("0");conversation.setClientId("client");conversation.setJiacn("owner");
        when(conversations.getOwned("owner","client","42")).thenReturn(conversation);
        when(deliberation.persistFinal(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),any(),any()))
                .thenReturn(new ChatDeliberationService.FinalResult(ChatDeliberationService.FinalStatus.PERSISTED,9L,"8","evt",turn(),null));
        String outcome="{\"schemaVersion\":2,\"kind\":\"ANSWER\",\"text\":\"你好🌏\",\"clarification\":null,\"proposal\":null}";
        String receipt="{\"schemaVersion\":1,\"authorizationId\":\"inspection-a\",\"manifestDigest\":\"sha256:"+"a".repeat(64)+"\",\"inputDigest\":\"sha256:"+"b".repeat(64)+"\",\"engineThreadId\":\"thread\",\"engineTurnId\":\"turn\",\"sources\":[]}";
        String wire=baseWire().replace("\"content\":\"plain\"","\"content\":\"你好🌏\"")
                +",\"outcomeContractVersion\":2,\"interactionOutcome\":"+outcome
                +",\"inspectionInputReceipt\":"+receipt+"}";
        handler.handleTextMessage(session,new TextMessage(wire));
        ArgumentCaptor<String> rawOutcome=ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rawReceipt=ArgumentCaptor.forClass(String.class);
        verify(deliberation).persistFinal(eq("0"),eq("owner"),eq("client"),eq("42"),eq(1L),eq("agent-a"),
                eq("request"),eq("turn"),eq("dispatch"),eq("snapshot"),eq("sha256:"+"a".repeat(64)),
                eq("你好🌏"),eq(2),rawOutcome.capture(),rawReceipt.capture(),any());
        assertEquals(outcome,rawOutcome.getValue());assertEquals(receipt,rawReceipt.getValue());
    }

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

    @Test void plainDurableFinalHasNoSynthesizedTypedSidecar() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        AgentWebSocketHandler handler=handler(provider);ChatConversationService conversations=mock(ChatConversationService.class);
        ChatDeliberationService deliberation=mock(ChatDeliberationService.class);handler.setChatConversationService(conversations);handler.setChatDeliberationService(deliberation);
        WebSocketSession session=session("plain");bind(handler,session);
        ChatConversationEntity conversation=new ChatConversationEntity().setId(42L).setConversationType("juyiting")
                .setTargetAgentIds("[\"agent-a\"]").setLifecycleGeneration(1L);conversation.setTenantId("0");conversation.setClientId("client");conversation.setJiacn("owner");
        when(conversations.getOwned("owner","client","42")).thenReturn(conversation);
        when(deliberation.persistFinal(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),isNull(),isNull(),isNull(),any()))
                .thenReturn(new ChatDeliberationService.FinalResult(ChatDeliberationService.FinalStatus.DUPLICATE,9L,"8","evt",turn(),null));
        handler.handleTextMessage(session,new TextMessage(baseWire()+"}"));
        verify(deliberation).persistFinal(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq("plain"),isNull(),isNull(),isNull(),any());
        ArgumentCaptor<TextMessage> receipt=ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(receipt.capture());
        assertTrue(receipt.getValue().getPayload().contains("\"type\":\"agent_message_saved\""));
        assertTrue(receipt.getValue().getPayload().contains("\"turnId\":\"turn\""));
        assertTrue(receipt.getValue().getPayload().contains("\"messageId\":\"9\""));
        assertTrue(receipt.getValue().getPayload().contains("\"duplicate\":true"));
        assertFalse(receipt.getValue().getPayload().contains("interactionOutcome"));
    }

    private static ChatTurnEntity turn(){return new ChatTurnEntity().setTurnId("turn");}
    private static String finalWire(String outcome){return baseWire().replace("\"content\":\"plain\"","\"content\":\"你好🌏\"")+",\"outcomeContractVersion\":1,\"interactionOutcome\":"+outcome+"}";}
    private static String baseWire(){return "{\"schemaVersion\":1,\"messageType\":\"chat.message\",\"messageId\":\"m1\",\"agentId\":\"agent-a\",\"sourceAgentId\":\"agent-a\",\"runtimeInstanceId\":\"runtime\",\"conversationId\":\"42\",\"conversationGeneration\":\"1\",\"requestId\":\"request\",\"turnId\":\"turn\",\"dispatchId\":\"dispatch\",\"contextSnapshotId\":\"snapshot\",\"contextHash\":\"sha256:"+"a".repeat(64)+"\",\"content\":\"plain\"";}
    private static AgentWebSocketHandler handler(ObjectProvider<AgentService> provider){return new AgentWebSocketHandler(mock(ChatClient.class),provider,mock(ChatMessageDao.class),new ChatConversationEventBroker());}
    private static WebSocketSession session(String id){WebSocketSession value=mock(WebSocketSession.class);when(value.getId()).thenReturn(id);when(value.isOpen()).thenReturn(true);when(value.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of("tenantId","0","jiacn","owner","clientId","client","agentId","agent-a","runtimeInstanceId","runtime")));return value;}
    @SuppressWarnings("unchecked") private static void bind(AgentWebSocketHandler handler,WebSocketSession session)throws Exception{((Map<String,WebSocketSession>)field(handler,"sessions")).put(session.getId(),session);((Map<String,Set<String>>)field(handler,"sessionAgentIds")).computeIfAbsent(session.getId(),x->ConcurrentHashMap.newKeySet()).add("agent-a");((Map<String,Set<String>>)field(handler,"successfullyRegisteredAgentIds")).computeIfAbsent(session.getId(),x->ConcurrentHashMap.newKeySet()).add("agent-a");}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
}
