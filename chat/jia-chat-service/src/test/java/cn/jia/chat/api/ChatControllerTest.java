package cn.jia.chat.api;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.entity.JsonResult;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.JuyitingAgentRelayService;
import cn.jia.chat.service.JuyitingAgentRelayResult;
import cn.jia.chat.service.JuyitingConversationScopeService;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.redis.RedisService;
import cn.jia.core.security.SensitiveResponseBodyAdvice;
import cn.jia.core.security.SensitiveResponseProperties;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    MemoryRepository memoryRepository;
    @Mock
    AgentTaskThreadMemoryGuard taskThreadMemoryGuard;
    @Mock
    AgentService agentService;

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
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class))).thenReturn(true);
        when(chatConversationEventBroker.stream(
                org.mockito.ArgumentMatchers.eq("1001"),
                org.mockito.ArgumentMatchers.eq(1L), any())).thenReturn(Flux.just("""
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
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), messageCaptor.capture(), eq(1L));
        verify(agentWebSocketHandler).sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class));

        ChatMessageEntity saved = messageCaptor.getValue();
        assertTrue("1001".equals(saved.getConversationId()));
        assertTrue("USER".equals(saved.getMessageType()));
        assertTrue("请吴用回报当前进度".equals(saved.getContent()));
        assertTrue(saved.getMetadata().contains("\"selectedAgentId\":\"agent-wuyong\""));
        assertTrue(chunks.stream().anyMatch(item -> item.contains("\"agentDelivery\"")));
        assertTrue(chunks.stream().anyMatch(item -> item.contains("\"conversationId\": \"1001\"")));
    }


    @Test
    void clientCancellationStillCancelsSlowChatBackendWithoutSyntheticTimeout() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);

        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(1001L)
                .setTitle("聚义厅议事")
                .setConversationType(ChatController.CONVERSATION_TYPE_JUYITING)
                .setLifecycleGeneration(1L);
        when(chatConversationService.get("1001")).thenReturn(conversation);
        when(redisService.subscribeToChannel("1001")).thenReturn(Flux.never());
        when(chatConversationEventBroker.deletionSignal(eq("1001"), eq(1L), any()))
                .thenReturn(Flux.never());
        when(chatConversationEventBroker.runIfLive(eq("1001"), eq(1L), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(3)).run();
                    return true;
                });
        java.util.concurrent.CountDownLatch subscribed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        JuyitingAgentRelayService relay = org.mockito.Mockito.mock(JuyitingAgentRelayService.class);
        when(relay.relay(any(), eq("1001"), any())).thenReturn(new JuyitingAgentRelayResult(
                true, Mono.just(true), Flux.<String>never()
                        .doOnSubscribe(ignored -> subscribed.countDown())
                        .doOnCancel(cancelled::countDown)));

        ChatController controller = new ChatController(
                chatClient, chatConversationService, redisService, chatClientBuilder,
                chatConversationEventBroker, builtinHallAgentSupport,
                new JuyitingConversationScopeService(builtinHallAgentSupport, agentService),
                relay, memoryRepository, taskThreadMemoryGuard);
        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationId("1001");
        request.setContent("请回报");

        reactor.core.Disposable subscription = controller.handleChat(request)
                .subscribe(ignored -> { }, failure::set);
        assertTrue(subscribed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        subscription.dispose();

        assertTrue(cancelled.await(1, java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertNull(failure.get());
    }

    @Test
    void genericChatCannotCreateReservedTaskThreadScope() {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("spoof task thread");
        request.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        request.setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        request.setConversationScopeKey("task-thread:task-1");
        request.setTaskId("task-1");

        AgentTaskThreadException denied = assertThrows(AgentTaskThreadException.class,
                () -> newController().handleChat(request));
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN, denied.getReason());

        ChatMessageDTO metadataSpoof = new ChatMessageDTO();
        metadataSpoof.setContent("spoof task thread through metadata");
        metadataSpoof.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        metadataSpoof.setMetadata(Map.of("mode",
                AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE));
        assertThrows(AgentTaskThreadException.class,
                () -> newController().handleChat(metadataSpoof));

        verify(chatConversationService, never()).create(any());
    }

    @Test
    void genericStopAndEventRoutesCannotUseTaskThreadConversation() {
        when(chatConversationService.get("77")).thenThrow(new AgentTaskThreadException(
                AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "protected task thread"));
        ChatController controller = newController();
        ChatMessageDTO stop = new ChatMessageDTO();
        stop.setConversationId("77");

        assertThrows(AgentTaskThreadException.class, () -> controller.stopStream(stop));
        assertThrows(AgentTaskThreadException.class, () -> controller.conversationEvents("77"));

        verify(redisService, never()).publishSignal("77");
        verify(chatConversationEventBroker, never()).stream(
                eq("77"), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(chatConversationEventBroker, never()).stream(
                eq("77"), org.mockito.ArgumentMatchers.anyLong(), any(), anyString());
    }

    @Test
    void conversationContentKeepsExactMessageIdsAfterSensitiveResponseAdvice() throws Exception {
        long userMessageId = 9_007_199_254_740_993L;
        long agentMessageId = 9_007_199_254_740_995L;
        String userMetadata = "{\"scene\":\"juyiting\",\"selectedAgentId\":\"agent-wuyong\"}";
        String agentMetadata = "{\"messageId\":\"9007199254740995\",\"agentId\":\"agent-wuyong\"}";
        ChatMessageEntity userMessage = new ChatMessageEntity()
                .setId(userMessageId)
                .setConversationId("1001")
                .setMessageType("USER")
                .setContent("请回报 password=hunter2")
                .setMetadata(userMetadata)
                .setJiacn("tester")
                .setSyncStatus("SYNCED")
                .setConversationType("juyiting")
                .setSenderType("user")
                .setSenderName("测试用户");
        userMessage.setCreateTime(100L);
        userMessage.setUpdateTime(101L);
        userMessage.setTenantId("0");
        userMessage.setClientId("web-client");
        ChatMessageEntity agentMessage = new ChatMessageEntity()
                .setId(agentMessageId)
                .setConversationId("1001")
                .setMessageType("ASSISTANT")
                .setContent("已收到")
                .setMetadata(agentMetadata)
                .setJiacn("tester")
                .setSyncStatus("SYNCED")
                .setConversationType("juyiting")
                .setSenderType("agent")
                .setSenderName("吴用");
        when(chatConversationService.findByConversationId("1001"))
                .thenReturn(List.of(userMessage, agentMessage));

        Object controllerBody = newController().getConversationContent("1001");
        SensitiveResponseBodyAdvice advice =
                new SensitiveResponseBodyAdvice(new SensitiveResponseProperties());
        Method method = ChatController.class.getDeclaredMethod("getConversationContent", String.class);
        Object sanitizedBody = advice.beforeBodyWrite(
                controllerBody, new MethodParameter(method, -1), MediaType.APPLICATION_JSON,
                null, null, null);
        String json = new ObjectMapper().writeValueAsString(sanitizedBody);
        Map<String, Object> wire = new ObjectMapper().readValue(json, new TypeReference<>() { });
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("data");

        assertEquals(2, messages.size());
        assertEquals("9007199254740993", messages.get(0).get("id"));
        assertEquals("9007199254740995", messages.get(1).get("id"));
        assertEquals(userMetadata, messages.get(0).get("metadata"));
        assertEquals(agentMetadata, messages.get(1).get("metadata"));
        assertEquals("user", messages.get(0).get("senderType"));
        assertEquals("agent", messages.get(1).get("senderType"));
        assertTrue(String.valueOf(messages.get(0).get("content")).contains("password=******"));
        assertTrue(!json.contains("hunter2"));
        verify(chatConversationService).findByConversationId("1001");
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
        setObjectField(request, "targetAgentIds", List.of("agent-wuyong", "agent-linchong"));
        when(agentService.listTaskWritableMemberAgentIds("tester", "web-client", "372"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
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
        assertEquals(null, getObjectField(captured, "targetAgentId"));
        assertEquals("[\"agent-wuyong\",\"agent-linchong\"]",
                getObjectField(captured, "targetAgentIds"));
        assertEquals(1L, getObjectField(captured, "lifecycleGeneration"));
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

        JuyitingConversationScopeService scopeService = new JuyitingConversationScopeService(builtinHallAgentSupport, agentService);
        assertEquals(List.of("songjiang"), scopeService.resolve(request).targetAgentIds());
    }

    @Test
    void bountyConversationRejectsTargetOutsideParticipantScope() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);

        ChatController controller = newController();

        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("请同步悬赏进度");
        request.setConversationType(ChatController.CONVERSATION_TYPE_JUYITING);
        setObjectField(request, "conversationScopeType", "bounty");
        setObjectField(request, "conversationScopeKey", "task:372");
        setObjectField(request, "taskId", "372");
        setObjectField(request, "targetAgentIds", List.of("agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-wuyong")));

        when(agentService.listTaskWritableMemberAgentIds("tester", "web-client", "372"))
                .thenReturn(List.of("agent-wuyong"));

        assertThrows(AgentTaskThreadException.class,
                () -> controller.handleChat(request).collectList().block());
        verify(chatConversationService, org.mockito.Mockito.never()).create(any());
        verify(chatConversationService, org.mockito.Mockito.never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createsTwoTargetBountyAndRelaysBothThroughControllerChain() {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);
        List<String> members = List.of("agent-wuyong", "agent-linchong");
        ChatController controller = newController();
        when(agentService.listTaskWritableMemberAgentIds("tester", "web-client", "task-7"))
                .thenReturn(members);
        when(chatConversationService.create(any(ChatConversationEntity.class)))
                .thenAnswer(invocation -> ((ChatConversationEntity) invocation.getArgument(0))
                        .setId(1001L));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenAnswer(invocation -> {
                    ChatConversationEntity value = new ChatConversationEntity()
                            .setId(1001L).setJiacn("tester").setConversationType("juyiting")
                            .setConversationScopeType("bounty")
                            .setConversationScopeKey("task:task-7")
                            .setTaskId("task-7")
                            .setTargetAgentIds("[\"agent-wuyong\",\"agent-linchong\"]")
                            .setLifecycleGeneration(1L);
                    value.setClientId("web-client");
                    value.setTenantId("0");
                    return value;
                });
        when(redisService.subscribeToChannel("1001")).thenReturn(Flux.never());
        for (String member : members) {
            when(agentWebSocketHandler.sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), any(Map.class)))
                    .thenReturn(true);
        }
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("两位都回报");
        request.setConversationType("juyiting");
        request.setConversationScopeType("bounty");
        request.setConversationScopeKey("task:task-7");
        request.setTaskId("task-7");
        request.setTargetAgentIds(members);

        List<String> chunks = controller.handleChat(request).collectList().block();

        ArgumentCaptor<ChatConversationEntity> created =
                ArgumentCaptor.forClass(ChatConversationEntity.class);
        verify(chatConversationService).create(created.capture());
        assertEquals(null, created.getValue().getTargetAgentId());
        assertEquals("[\"agent-wuyong\",\"agent-linchong\"]",
                created.getValue().getTargetAgentIds());
        for (String member : members) {
            verify(agentWebSocketHandler).sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), any(Map.class));
        }
        assertEquals(2, chunks.stream().filter(value -> value.contains("agentDelivery")).count());
    }

    @Test
    void deletingConversationCompletesExistingSseSubscriberWithoutEvent() throws Exception {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(5001L).setLifecycleGeneration(1L);
        when(chatConversationService.get("5001")).thenReturn(conversation);
        when(chatConversationService.isLiveGeneration(
                "tester", "web-client", "5001", 1L)).thenReturn(true);
        JuyitingConversationScopeService scopeService =
                new JuyitingConversationScopeService(builtinHallAgentSupport, agentService);
        ChatController controller = new ChatController(
                chatClient, chatConversationService, redisService, chatClientBuilder,
                broker, builtinHallAgentSupport, scopeService,
                new JuyitingAgentRelayService(
                        agentWebSocketHandler, broker, builtinHallAgentSupport,
                        chatConversationService, agentService, scopeService),
                memoryRepository, taskThreadMemoryGuard);
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch completed =
                new java.util.concurrent.CountDownLatch(1);
        controller.conversationEvents("5001").subscribe(
                event -> { events.add(event); ready.countDown(); },
                ignored -> { }, completed::countDown);
        assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS));

        try (ChatConversationEventBroker.DeletionFence fence = broker.beginDeletion("5001")) {
            fence.commitDeleted(1L);
        }

        assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(List.of("data: {\"type\":\"stream_ready\"}\n\n"), events);
        assertTrue(!broker.publishIfLive("5001", 1L, () -> false,
                Map.of("type", "agent_message")));
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
        org.mockito.Mockito.lenient().when(taskThreadMemoryGuard.excludeProtected(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ChatConversationEntity publicConversation = new ChatConversationEntity()
                .setId(1001L).setJiacn("tester").setConversationType("juyiting")
                .setConversationScopeType("public").setConversationScopeKey("public")
                .setTargetAgentIds("[\"agent-wuyong\"]");
        publicConversation.setTenantId("0"); publicConversation.setClientId("web-client");
        org.mockito.Mockito.lenient().when(chatConversationService.getOwned(
                "tester", "web-client", "1001")).thenReturn(publicConversation);
        org.mockito.Mockito.lenient().when(chatConversationService.appendOwnedMessage(
                anyString(), anyString(), any(ChatMessageEntity.class),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        org.mockito.Mockito.lenient().when(chatConversationService.isLiveGeneration(
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(chatConversationEventBroker.deletionSignal(
                anyString(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(Flux.never());
        org.mockito.Mockito.lenient().when(chatConversationEventBroker.runIfLive(
                anyString(), org.mockito.ArgumentMatchers.anyLong(), any(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(3)).run();
                    return true;
                });
        JuyitingConversationScopeService scopeService = new JuyitingConversationScopeService(builtinHallAgentSupport, agentService);
        JuyitingAgentRelayService relayService = new JuyitingAgentRelayService(
                agentWebSocketHandler,
                chatConversationEventBroker,
                builtinHallAgentSupport,
                chatConversationService,
                agentService,
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
                memoryRepository,
                taskThreadMemoryGuard
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
