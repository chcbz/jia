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
import cn.jia.chat.service.JuyitingAgentRelayService;
import cn.jia.chat.service.JuyitingConversationScopeService;
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

        ChatController controller = newController();

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
    void createsScopedJuyitingConversationFromRequestFields() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);

        ChatController controller = newController();

        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        setObjectField(request, "conversationScopeType", "bounty");
        setObjectField(request, "conversationScopeKey", "task:372");
        setObjectField(request, "taskId", "372");
        setObjectField(request, "targetAgentId", "agent-wuyong");
        when(chatConversationService.create(any(ChatConversationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatConversationEntity created = invokeGetOrCreateConversation(controller, request);

        ArgumentCaptor<ChatConversationEntity> conversationCaptor = ArgumentCaptor.forClass(ChatConversationEntity.class);
        verify(chatConversationService).create(conversationCaptor.capture());
        ChatConversationEntity captured = conversationCaptor.getValue();

        assertEquals(ChatController.CONVERSATION_TYPE_JUYITING, captured.getConversationType());
        assertEquals("bounty", getObjectField(captured, "conversationScopeType"));
        assertEquals("task:372", getObjectField(captured, "conversationScopeKey"));
        assertEquals("372", getObjectField(captured, "taskId"));
        assertEquals("agent-wuyong", getObjectField(captured, "targetAgentId"));
        assertEquals(created, captured);
    }

    @Test
    void listConversationsPassesScopeFiltersToService() throws Exception {
        ChatController controller = newController();
        when(chatConversationService.findPage(any(), eq(1), eq(1), eq("update_time desc")))
                .thenReturn(new com.github.pagehelper.PageInfo<>(List.of()));

        ChatConversationEntity search = new ChatConversationEntity();
        search.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        setObjectField(search, "conversationScopeType", "private");
        setObjectField(search, "conversationScopeKey", "task:372:agent:agent-wuyong");

        cn.jia.core.entity.JsonRequestPage<ChatConversationEntity> page = new cn.jia.core.entity.JsonRequestPage<>();
        page.setPageNum(1);
        page.setPageSize(1);
        page.setOrderBy("update_time desc");
        page.setSearch(search);

        controller.listConversations(page);

        ArgumentCaptor<ChatConversationEntity> searchCaptor = ArgumentCaptor.forClass(ChatConversationEntity.class);
        verify(chatConversationService).findPage(searchCaptor.capture(), eq(1), eq(1), eq("update_time desc"));
        ChatConversationEntity captured = searchCaptor.getValue();
        assertEquals(ChatController.CONVERSATION_TYPE_JUYITING, captured.getConversationType());
        assertEquals("private", getObjectField(captured, "conversationScopeType"));
        assertEquals("task:372:agent:agent-wuyong", getObjectField(captured, "conversationScopeKey"));
    }

    @Test
    void publicHallConversationWithoutTargetResolvesToSongJiang() throws Exception {
        when(builtinHallAgentSupport.defaultAgentId()).thenReturn("songjiang");

        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        setObjectField(request, "conversationScopeType", "public");
        setObjectField(request, "conversationScopeKey", "public");

        JuyitingConversationScopeService scopeService = new JuyitingConversationScopeService(builtinHallAgentSupport);
        assertEquals(List.of("songjiang"), scopeService.resolve(request).targetAgentIds());
    }

    @Test
    void bountyConversationRejectsTargetOutsideParticipantScope() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);

        ChatConversationEntity conversation = new ChatConversationEntity();
        conversation.setId(372L);
        conversation.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        setObjectField(conversation, "conversationScopeType", "bounty");
        setObjectField(conversation, "conversationScopeKey", "task:372");

        when(chatConversationService.create(any(ChatConversationEntity.class))).thenReturn(conversation);
        when(redisService.subscribeToChannel("372")).thenReturn(Flux.never());

        ChatController controller = newController();

        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("请同步悬赏进度");
        request.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        setObjectField(request, "conversationScopeType", "bounty");
        setObjectField(request, "conversationScopeKey", "task:372");
        setObjectField(request, "taskId", "372");
        setObjectField(request, "targetAgentIds", List.of("agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-wuyong")));

        List<String> chunks = controller.handleChat(request).collectList().block();

        assertTrue(chunks.stream().anyMatch(item -> item.contains("target outside bounty participants")));
        verify(chatMessageDao, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void searchLibraryReturnsEmptyResultsWhenMemorySearchFails() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        EsContextHolder.setContext(context);

        when(memoryRepository.searchWithConversationBoost(anyString(), anyString(), any(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("memory index unavailable"));

        ChatController controller = newController();

        JsonResult<?> result = invokeSearchLibrary(controller, "宋江");

        assertEquals("E0", result.getCode());
        assertEquals(List.of(), result.getData());
    }

    @Test
    void seedLibraryDocumentStoresProjectMemory() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        EsContextHolder.setContext(context);

        ChatController controller = newController();

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

    private ChatController newController() {
        JuyitingConversationScopeService scopeService = new JuyitingConversationScopeService(builtinHallAgentSupport);
        JuyitingAgentRelayService relayService = new JuyitingAgentRelayService(
                agentWebSocketHandler,
                chatConversationEventBroker,
                builtinHallAgentSupport,
                chatMessageDao,
                scopeService
        );
        return new ChatController(
                chatClient,
                chatConversationService,
                redisService,
                chatClientBuilder,
                chatConversationEventBroker,
                builtinHallAgentSupport,
                scopeService,
                relayService,
                chatMessageDao,
                memoryRepository
        );
    }

    private ChatConversationEntity invokeGetOrCreateConversation(ChatController controller, ChatMessageDTO request)
            throws Exception {
        Method method = ChatController.class.getDeclaredMethod("getOrCreateConversation", ChatMessageDTO.class);
        method.setAccessible(true);
        return (ChatConversationEntity) method.invoke(controller, request);
    }

    private Object getObjectField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setObjectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
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
