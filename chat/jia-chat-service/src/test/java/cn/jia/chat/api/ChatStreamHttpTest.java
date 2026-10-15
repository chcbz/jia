package cn.jia.chat.api;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.*;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real controller and MVC data-only SSE encoder without any model/DB call. */
class ChatStreamHttpTest {
    private JuyitingAgentRelayService relay;
    private MockMvc mvc;
    private ChatClient model;

    @BeforeEach void setUp() {
        var conversations = mock(ChatConversationService.class);
        var conversation = new ChatConversationEntity().setId(42L).setJiacn("owner")
                .setTitle("existing title").setConversationType("juyiting").setLifecycleGeneration(1L);
        conversation.setTenantId("0"); conversation.setClientId("client");
        when(conversations.get("42")).thenReturn(conversation);
        when(conversations.isLiveGeneration("owner", "client", "42", 1L)).thenReturn(true);
        var redis = mock(RedisService.class);
        when(redis.subscribeToChannel("42")).thenReturn(Flux.never());
        var identity = mock(HumanSenderIdentityResolver.class);
        when(identity.resolve(any())).thenReturn(new ServerResolvedSender("user", "Owner", "owner", "client", DisplayNameSource.JIACN));
        relay = mock(JuyitingAgentRelayService.class);
        model = mock(ChatClient.class);
        var controller = new ChatController(model, conversations, redis, mock(ChatClient.Builder.class),
                new ChatConversationEventBroker(), mock(BuiltinHallAgentSupport.class), mock(JuyitingConversationScopeService.class),
                relay, mock(MemoryRepository.class), mock(AgentTaskThreadMemoryGuard.class), identity);
        mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(new RequestIdFilter()).build();
        var scope = new EsContext(); scope.setTenantId("0"); scope.setJiacn("owner"); scope.setClientId("client");
        EsContextHolder.setContext(scope);
    }
    @AfterEach void clear() { EsContextHolder.clearContext(); }

    @Test void actualSseKeepsTextPrivateIdentityAndLargeRevision() throws Exception {
        frames(Flux.just("{\"v\":\"中文\\n\\\"quoted\\\"\"}",
                "{\"requestId\":\"durable\",\"eventId\":\"stable-event\",\"eventVersion\":\"9007199254740993\",\"occurredAt\":\"1000\"}"));
        var wire = read();
        assertEquals(4, wire.size()); // first reference + two relay frames + final reference
        assertEquals("chat.stream.delta", wire.get(1).get("messageType"));
        assertEquals("中文\n\"quoted\"", ((Map<?, ?>) wire.get(1).get("data")).get("v"));
        assertEquals("stable-event", wire.get(2).get("messageId"));
        var data = (Map<?, ?>) wire.get(2).get("data");
        assertEquals("durable", data.get("requestId"));
        assertEquals("9007199254740993", data.get("eventVersion"));
        assertEquals(1000, data.get("occurredAt"));
        verifyNoInteractions(model);
    }
    @Test void backendFailureIsAnExplicitSafeErrorNotSuccessfulCompletion() throws Exception {
        frames(Flux.error(new IllegalStateException("provider password=secret")));
        var wire = read();
        assertEquals("chat.stream.error", wire.getLast().get("messageType"));
        assertFalse(JsonUtil.toJson(wire).contains("secret"));
        assertTrue(wire.stream().noneMatch(frame -> "chat.stream.completed".equals(frame.get("messageType"))));
    }
    private void frames(Flux<String> stream) {
        when(relay.relay(any(), eq("42"), any(), any())).thenReturn(new JuyitingAgentRelayResult(true, Mono.just(true), stream));
    }
    private List<Map> read() throws Exception {
        String requestJson = "{\"protocolVersion\":1,\"requestId\":\"transport\",\"data\":{\"content\":\"hello\",\"conversationId\":\"42\",\"conversationType\":\"juyiting\"}}";
        var pending = mvc.perform(post("/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "transport").content(requestJson).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "transport"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(body.contains("data:data:"));
        return body.lines().filter(line -> line.startsWith("data:")).map(line -> {
            Map message = JsonUtil.getMapper().readValue(line.substring(5).stripLeading(), Map.class);
            assertEquals(Set.of("protocolVersion", "requestId", "messageType", "messageId", "data"), message.keySet());
            assertEquals("transport", message.get("requestId"));
            return message;
        }).toList();
    }
}
