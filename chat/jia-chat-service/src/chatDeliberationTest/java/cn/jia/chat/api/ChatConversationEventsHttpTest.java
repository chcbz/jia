package cn.jia.chat.api;

import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.*;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC reactive SSE encoder, not direct Flux strings or a mocked HTTP response. */
class ChatConversationEventsHttpTest {
    private ChatDeliberationService events;
    private ChatConversationEventBroker broker;
    private ChatConversationEventBroker.LiveSubscription subscription;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        var conversations=mock(ChatConversationService.class);
        var conversation=new ChatConversationEntity().setId(42L).setJiacn("owner").setLifecycleGeneration(3L);
        conversation.setTenantId("0");conversation.setClientId("client");
        when(conversations.get("42")).thenReturn(conversation);
        broker=mock(ChatConversationEventBroker.class);
        subscription=mock(ChatConversationEventBroker.LiveSubscription.class);
        when(broker.subscribeBuffered(eq("42"),eq(3L),any())).thenReturn(subscription);
        when(subscription.flux()).thenReturn(Flux.empty());
        events=mock(ChatDeliberationService.class);
        when(events.eventHighWatermark("0","owner","client","42",3)).thenReturn(7L);
        when(events.replayEventsThrough("0","owner","client","42",3,0,7,500))
                .thenReturn(List.of(event(7,"interaction.state_changed","{\"requestId\":\"request\",\"state\":\"PLANNING\"}")));
        var controller=new ChatController(mock(ChatClient.class),conversations,mock(RedisService.class),
                mock(ChatClient.Builder.class),broker,mock(BuiltinHallAgentSupport.class),
                mock(JuyitingConversationScopeService.class),mock(JuyitingAgentRelayService.class),
                mock(MemoryRepository.class),mock(AgentTaskThreadMemoryGuard.class),mock(HumanSenderIdentityResolver.class));
        controller.setChatDeliberationService(events);
        mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var scope=new EsContext();scope.setTenantId("0");scope.setJiacn("owner");scope.setClientId("client");EsContextHolder.setContext(scope);
    }
    @AfterEach void clear() { EsContextHolder.clearContext(); }

    @Test void replayAndReadyAreSingleEncodedWithDurableTypeScopeAndCursor() throws Exception {
        String body=read(null);
        assertFalse(body.contains("data:data:"),body);
        assertFalse(body.contains("data:id:"),body);
        assertTrue(body.contains("id:7"),body);
        var data=payloads(body);assertEquals(2,data.size());
        assertEquals("interaction.state_changed",data.get(0).get("type"));
        assertEquals("42",data.get(0).get("conversationId"));
        assertEquals("3",data.get(0).get("conversationGeneration"));
        assertEquals("7",data.get(0).get("eventSequence"));
        assertEquals("stream_ready",data.get(1).get("type"));assertEquals("7",data.get(1).get("nextCursor"));
        verify(subscription).close();
    }

    @Test void liveSignalCatchesUpDurableMediaAndLastEventIdDoesNotReplayPriorFrames() throws Exception {
        when(subscription.flux()).thenReturn(Flux.just("{\"eventSequence\":\"8\"}"));
        when(events.replayEventsThrough("0","owner","client","42",3,7,8,500))
                .thenReturn(List.of(event(8,"agent_message","{\"messageId\":\"101\",\"parts\":[{\"kind\":\"image\",\"state\":\"ready\",\"assetId\":\"asset\"}]}")));
        String body=read("7");assertFalse(body.contains("data:data:"),body);
        var data=payloads(body);assertEquals(2,data.size());assertEquals("stream_ready",data.get(0).get("type"));
        assertEquals("agent_message",data.get(1).get("type"));assertEquals("8",data.get(1).get("eventSequence"));
        assertTrue(body.contains("id:8"),body);assertFalse(body.contains("id:7"),body);
        assertEquals("ready",((Map<?,?>)((List<?>)data.get(1).get("parts")).getFirst()).get("state"));
        verify(events,never()).replayEventsThrough("0","owner","client","42",3,0,7,500);
    }

    @Test void explicitSseAcceptStillGetsReadableNonSuccessJsonForDeniedIdentity() throws Exception {
        mvc.perform(get("/chat/conversation/events").param("id","42").accept(MediaType.TEXT_EVENT_STREAM)
                        .principal(jwt("foreign")))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("CHAT_NOT_FOUND"));
        verifyNoInteractions(events);
    }

    private String read(String cursor) throws Exception {
        var request=get("/chat/conversation/events").param("id","42").principal(jwt("0"))
                .accept(MediaType.TEXT_EVENT_STREAM);
        if(cursor!=null)request.header("Last-Event-ID",cursor);
        var pending=mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andReturn().getResponse().getContentAsString();
    }
    private static List<Map> payloads(String body) {
        return body.lines().filter(line->line.startsWith("data:")).map(line->JsonUtil.getMapper().readValue(line.substring(5).stripLeading(),Map.class)).toList();
    }
    private static ChatConversationEventEntity event(long sequence,String type,String payload) {
        return new ChatConversationEventEntity().setEventId("event-"+sequence).setEventSequence(sequence)
                .setEventVersion(sequence).setConversationId("42").setConversationGeneration(3L)
                .setEventType(type).setPayloadJson(payload).setOccurredAt(1000L);
    }
    private static JwtAuthenticationToken jwt(String tenant) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","none").subject("owner")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("tenant_id",tenant)
                .claim("jiacn","owner").claim("client_id","client").build());
    }
}
