package cn.jia.chat.api;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.entity.JsonResult;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.redis.RedisService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest extends BaseMockTest {
    @Mock
    ChatClient chatClient;
    @Mock
    ChatConversationService chatConversationService;
    @Mock
    RedisService redisService;
    @Mock
    ChatClient.Builder chatClientBuilder;
    @Mock
    AgentWebSocketHandler agentWebSocketHandler;
    @Mock
    ChatConversationEventBroker chatConversationEventBroker;
    @Mock
    BuiltinHallAgentSupport builtinHallAgentSupport;
    @Mock
    ChatMessageDao chatMessageDao;
    @Mock
    MemoryRepository memoryRepository;

    @AfterEach
    void tearDown() {
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void savesUserMessageBeforeRelayingToJuyitingAgent() {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);

        ChatConversationEntity conversation = new ChatConversationEntity();
        conversation.setId(1001L);
        conversation.setTitle("聚义厅议事");
        conversation.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);

        when(chatConversationService.create(any(ChatConversationEntity.class))).thenReturn(conversation);
        when(redisService.subscribeToChannel("1001")).thenReturn(Flux.never());
        when(agentWebSocketHandler.isAgentConnected("agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(eq("agent-wuyong"), any(Map.class))).thenReturn(true);
        when(chatConversationEventBroker.stream("1001")).thenReturn(Flux.just("""
                {"type":"agent_message","conversationId":"1001","conversationType":"juyiting","agentId":"agent-wuyong","senderType":"agent","senderName":"Wu Yong","content":"ok"}
                """).delayElements(Duration.ofMillis(10)));

        ChatController controller = new ChatController(
                chatClient,
                chatConversationService,
                redisService,
                chatClientBuilder,
                agentWebSocketHandler,
                chatConversationEventBroker,
                builtinHallAgentSupport,
                chatMessageDao,
                memoryRepository
        );

        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("请吴用回报当前进度");
        request.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        request.setSenderType("user");
        request.setSenderName("测试用户");
        request.setMetadata(Map.of("selectedAgentId", "agent-wuyong"));

        List<String> chunks = controller.handleChat(request).collectList().block();

        ArgumentCaptor<ChatMessageEntity> messageCaptor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageDao).insert(messageCaptor.capture());
        verify(agentWebSocketHandler).sendDirectMessageToAgent(eq("agent-wuyong"), any(Map.class));

        ChatMessageEntity saved = messageCaptor.getValue();
        assertTrue("1001".equals(saved.getConversationId()));
        assertTrue("USER".equals(saved.getMessageType()));
        assertTrue("请吴用回报当前进度".equals(saved.getContent()));
        assertTrue(saved.getMetadata().contains("\"selectedAgentId\":\"agent-wuyong\""));
        assertTrue(chunks.stream().anyMatch(item -> item.contains("\"agentDelivery\"")));
        assertTrue(chunks.stream().anyMatch(item -> item.contains("\"conversationId\": \"1001\"")));
    }

    @Test
    void searchLibraryReturnsEmptyResultsWhenMemorySearchFails() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        EsContextHolder.setContext(context);

        when(memoryRepository.searchWithConversationBoost(anyString(), anyString(), any(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("memory index unavailable"));

        ChatController controller = new ChatController(
                chatClient,
                chatConversationService,
                redisService,
                chatClientBuilder,
                agentWebSocketHandler,
                chatConversationEventBroker,
                builtinHallAgentSupport,
                chatMessageDao,
                memoryRepository
        );

        JsonResult<?> result = invokeSearchLibrary(controller, "宋江");

        assertEquals("E0", result.getCode());
        assertEquals(List.of(), result.getData());
    }

    @Test
    void seedLibraryDocumentStoresProjectMemory() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        EsContextHolder.setContext(context);

        ChatController controller = new ChatController(
                chatClient,
                chatConversationService,
                redisService,
                chatClientBuilder,
                agentWebSocketHandler,
                chatConversationEventBroker,
                builtinHallAgentSupport,
                chatMessageDao,
                memoryRepository
        );

        JsonResult<?> result = invokeSeedLibraryDocument(
                controller,
                "聚义厅本地启动说明",
                "后端灰度启动命令包含 jasypt.encryptor.password=cyf0519",
                "local-startup",
                List.of("project", "juyiting", "public-beta")
        );

        ArgumentCaptor<MemoryDocument> documentCaptor = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository).save(documentCaptor.capture());
        MemoryDocument document = documentCaptor.getValue();
        assertEquals("tester", document.getJiacn());
        assertEquals("project", document.getSummaryType());
        assertEquals("local-startup", document.getTopic());
        assertEquals(List.of("project", "juyiting", "public-beta"), document.getCategories());
        assertTrue(document.getContent().contains("聚义厅本地启动说明"));
        assertTrue(document.getContent().contains("cyf0519"));
        assertEquals("E0", result.getCode());
    }

    private JsonResult<?> invokeSearchLibrary(ChatController controller, String keyword) throws Exception {
        Class<?> requestClass = Class.forName("cn.jia.chat.api.ChatController$LibrarySearchRequest");
        Constructor<?> constructor = requestClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object request = constructor.newInstance();
        setField(requestClass, request, "keyword", keyword);
        setField(requestClass, request, "topK", 8);

        Method method = ChatController.class.getDeclaredMethod("searchLibrary", requestClass);
        return (JsonResult<?>) method.invoke(controller, request);
    }

    private JsonResult<?> invokeSeedLibraryDocument(ChatController controller, String title, String content,
                                                    String topic, List<String> categories) throws Exception {
        Class<?> requestClass = Class.forName("cn.jia.chat.api.ChatController$LibraryDocumentRequest");
        Constructor<?> constructor = requestClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object request = constructor.newInstance();
        setField(requestClass, request, "title", title);
        setField(requestClass, request, "content", content);
        setField(requestClass, request, "topic", topic);
        setField(requestClass, request, "categories", categories);

        Method method = ChatController.class.getDeclaredMethod("saveLibraryDocument", requestClass);
        return (JsonResult<?>) method.invoke(controller, request);
    }

    private void setField(Class<?> targetClass, Object target, String fieldName, Object value) throws Exception {
        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
