package cn.jia.chat.api;

import cn.jia.chat.serialization.ExactWireIds;

import cn.jia.chat.advisor.DatabaseChatMemoryAdvisor;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.StringUtil;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.JuyitingAgentRelayResult;
import cn.jia.chat.service.JuyitingAgentRelayService;
import cn.jia.chat.service.JuyitingConversationScope;
import cn.jia.chat.service.JuyitingConversationScopeService;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import com.github.pagehelper.PageInfo;
import io.micrometer.core.instrument.util.StringEscapeUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import reactor.core.Disposable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent聊天控制器
 * 提供基于Spring AI的流式聊天功能，支持会话管理和流传输控制
 *
 * @author jia
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {
    public static final String CONVERSATION_TYPE_NORMAL = "normal";
    public static final String CONVERSATION_TYPE_JUYITING = "juyiting";

    private final ChatClient chatClient;
    private final ChatConversationService chatConversationService;
    private final RedisService redisService;
    private final ChatClient.Builder chatClientBuilder;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final BuiltinHallAgentSupport builtinHallAgentSupport;
    private final JuyitingConversationScopeService juyitingConversationScopeService;
    private final JuyitingAgentRelayService juyitingAgentRelayService;
    private final MemoryRepository memoryRepository;
    private final AgentTaskThreadMemoryGuard taskThreadMemoryGuard;
    private static final PromptTemplate SUMMARY_PROMPT_TEMPLATE = new PromptTemplate("""
            帮我根据下面对话内容，输出15字以内的问题意图概述，需要名词开头。
            
            问：
            -------------------------------------------------
            {question}
            -------------------------------------------------
            答：
            -------------------------------------------------
            {answer}
            -------------------------------------------------
            """);

    /**
     * 处理聊天请求并返回流式响应
     *
     * @param chatMessage 包含用户消息和会话ID的DTO对象
     * @return 返回包含AI回复内容的流
     */
    @RequestMapping(value = "/stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleChat(@RequestBody ChatMessageDTO chatMessage) {
        ChatConversationEntity conversation = getOrCreateConversation(chatMessage);
        boolean needSummary = StringUtil.isBlank(conversation.getTitle());
        StringBuilder summary = new StringBuilder();
        String conversationId = String.valueOf(conversation.getId());
        String ownerJiacn = requireIdentityPart(EsContextHolder.getContext().getJiacn());
        String ownerClientId = requireIdentityPart(EsContextHolder.getContext().getClientId());
        long generation = lifecycleGeneration(conversation);

        Flux<String> cancelSignal = redisService.subscribeToChannel(conversationId);
        JuyitingAgentRelayResult agentDelivery = juyitingAgentRelayService.relay(
                chatMessage,
                conversationId,
                () -> createBuiltinSongJiangStream(
                        chatMessage, conversationId, ownerJiacn, ownerClientId,
                        generation, needSummary, summary)
        );
        boolean skipAdvisorUserPersistence = agentDelivery.attempted();
        Flux<String> aiStream = agentDelivery.delivered().flatMapMany(delivered -> delivered
                ? Flux.empty()
                : createAIStream(chatMessage, conversationId, ownerJiacn, ownerClientId,
                        resolveConversationType(conversation), needSummary, summary,
                        skipAdvisorUserPersistence));

        return Flux.create(emitter -> {
            Flux<String> backendStream = agentDelivery.stream()
                    .concatWith(aiStream)
                    .concatWith(Flux.defer(() -> Flux.just("{\"conversationId\": \"" + conversationId + "\", \"conversationType\": \"" + resolveConversationType(conversation) + "\"}")))
                    .concatWith(Flux.defer(() -> handleSummary(
                            needSummary, chatMessage.getContent(), summary.toString(),
                            conversationId, ownerJiacn, ownerClientId)))
                    .takeUntilOther(cancelSignal)
                    .takeUntilOther(chatConversationEventBroker.deletionSignal(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation)))
                    .doOnNext(value -> chatConversationEventBroker.runIfLive(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation),
                            () -> emitter.next(value)))
                    .doOnError(error -> {
                        log.error("Error processing chat response", error);
                        String errorMsg = error.getMessage() != null ? error.getMessage() : "Stream error";
                        String escapedMsg = errorMsg.replace("\\", "\\\\").replace("\"", "\\\"");
                        chatConversationEventBroker.runIfLive(
                                conversationId, generation,
                                () -> chatConversationService.isLiveGeneration(
                                        ownerJiacn, ownerClientId, conversationId, generation),
                                () -> emitter.next("{\"error\": \"" + escapedMsg
                                        + "\", \"conversationId\": \"" + conversationId
                                        + "\", \"conversationType\": \""
                                        + resolveConversationType(conversation) + "\"}"));
                        emitter.complete();
                    })
                    .doOnComplete(emitter::complete)
                    .subscribeOn(Schedulers.boundedElastic());

            Disposable backendSubscription = backendStream.subscribe();

            emitter.onDispose(() -> {
                backendSubscription.dispose();
                log.debug("Client disconnected, backend processing stopped for conversation: {}", conversationId);
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private ChatConversationEntity getOrCreateConversation(ChatMessageDTO chatMessage) {
        ChatConversationEntity message;
        if (StringUtil.isBlank(chatMessage.getConversationId()) || Boolean.TRUE.equals(chatMessage.getForceNewConversation())) {
            message = new ChatConversationEntity();
            message.setStatus(0);
            message.setJiacn(EsContextHolder.getContext().getJiacn());
            message.setClientId(EsContextHolder.getContext().getClientId());
            message.setConversationType(
                    StringUtil.isNotBlank(chatMessage.getConversationType())
                            ? chatMessage.getConversationType()
                            : CONVERSATION_TYPE_NORMAL
            );
            if (CONVERSATION_TYPE_JUYITING.equals(message.getConversationType())) {
                if (requestsReservedTaskThreadScope(chatMessage)) {
                    throw reservedTaskThreadAccess();
                }
                JuyitingConversationScope scope;
                try {
                    scope = juyitingConversationScopeService.authorize(
                            chatMessage,
                            juyitingConversationScopeService.resolve(chatMessage),
                            requireIdentityPart(EsContextHolder.getContext().getJiacn()),
                            requireIdentityPart(EsContextHolder.getContext().getClientId()));
                } catch (RuntimeException denied) {
                    throw new AgentTaskThreadException(
                            AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                            "Conversation scope is unavailable");
                }
                if (AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE.equals(scope.scopeType())) {
                    throw reservedTaskThreadAccess();
                }
                message.setConversationScopeType(scope.scopeType());
                message.setConversationScopeKey(scope.scopeKey());
                message.setTaskId(scope.taskId());
                message.setTargetAgentId(
                        JuyitingConversationScopeService.SCOPE_PRIVATE.equals(scope.scopeType())
                                ? scope.targetAgentId() : null);
                message.setTargetAgentIds(
                        juyitingConversationScopeService.serializeTargetAgentIds(
                                scope.targetAgentIds()));
                message.setLifecycleGeneration(1L);
            }
            message = chatConversationService.create(message);
        } else {
            message = chatConversationService.get(chatMessage.getConversationId());
        }
        return message;
    }

    private boolean requestsReservedTaskThreadScope(ChatMessageDTO chatMessage) {
        if (chatMessage == null) {
            return false;
        }
        ChatConversationEntity requestedScope = new ChatConversationEntity()
                .setConversationScopeType(chatMessage.getConversationScopeType());
        if (AgentTaskThreadConstants.hasTaskThreadMarkerEvidence(requestedScope)) {
            return true;
        }
        Object metadataMode = chatMessage.getMetadata() == null
                ? null : chatMessage.getMetadata().get("mode");
        return metadataMode != null
                && AgentTaskThreadConstants.hasTaskThreadMarkerEvidence(
                new ChatConversationEntity().setConversationScopeType(String.valueOf(metadataMode)));
    }

    private AgentTaskThreadException reservedTaskThreadAccess() {
        return new AgentTaskThreadException(
                AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Reserved conversation scope type");
    }

    private void requireGenericConversationAccess(String conversationId) {
        if (StringUtil.isNotBlank(conversationId)) {
            chatConversationService.get(conversationId);
        }
    }

    private String resolveConversationType(ChatConversationEntity conversation) {
        if (conversation == null) {
            return CONVERSATION_TYPE_NORMAL;
        }
        return StringUtil.isNotBlank(conversation.getConversationType())
                ? conversation.getConversationType()
                : CONVERSATION_TYPE_NORMAL;
    }

    private Flux<String> createAIStream(
            ChatMessageDTO chatMessage, String conversationId,
            String ownerJiacn, String ownerClientId, String conversationType,
            boolean needSummary, StringBuilder summary,
            boolean skipAdvisorUserPersistence) {
        String filterExpression = "metadata.jiacn == '" + ownerJiacn + "' AND role == 'ASSISTANT'";
        
        return chatClient.prompt(
                        Prompt.builder().messages(UserMessage.builder().text(chatMessage.getContent()).build()).build())
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("jiacn", ownerJiacn)
                        .param("clientId", ownerClientId)
                        .param("conversationType", conversationType)
                        .param("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse(""))
                        .param("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse(""))
                        .param("selectedAgentId", Optional.ofNullable(juyitingAgentRelayService.selectedAgentId(chatMessage)).orElse(""))
                        .param(DatabaseChatMemoryAdvisor.SKIP_USER_MESSAGE_PERSISTENCE, skipAdvisorUserPersistence)
                        .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filterExpression))
                .messages()
                .stream().content()
                .map(content -> processContent(content, needSummary, summary));
    }

    private Flux<String> createBuiltinSongJiangStream(
            ChatMessageDTO chatMessage, String conversationId,
            String ownerJiacn, String ownerClientId, long generation,
            boolean needSummary, StringBuilder summary) {
        StringBuilder answer = new StringBuilder();
        Flux<String> deliveryEvent = Flux.just(buildAgentDeliveryEventJson(conversationId, builtinHallAgentSupport.defaultAgentId(), true));

        Flux<String> deltaStream = chatClient.prompt(
                        Prompt.builder().messages(UserMessage.builder().text(chatMessage.getContent()).build()).build())
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("jiacn", ownerJiacn)
                        .param("clientId", ownerClientId)
                        .param("conversationType", CONVERSATION_TYPE_JUYITING)
                        .param("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse("user"))
                        .param("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"))
                        .param("selectedAgentId", builtinHallAgentSupport.defaultAgentId())
                        .param(DatabaseChatMemoryAdvisor.SKIP_USER_MESSAGE_PERSISTENCE, true)
                        .param(DatabaseChatMemoryAdvisor.SKIP_ASSISTANT_MESSAGE_PERSISTENCE, true)
                        .param(QuestionAnswerAdvisor.FILTER_EXPRESSION,
                                "metadata.jiacn == '" + ownerJiacn + "' AND role == 'ASSISTANT'"))
                .messages()
                .stream().content()
                .map(chunk -> {
                    if (!chatConversationService.isLiveGeneration(
                            ownerJiacn, ownerClientId, conversationId, generation)) {
                        throw new IllegalStateException("Conversation is no longer available");
                    }
                    if (needSummary) {
                        summary.append(chunk);
                    }
                    answer.append(chunk);
                    Map<String, Object> event = buildAgentEvent("agent_message_delta", conversationId, chunk, null);
                    chatConversationEventBroker.publishIfLive(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation),
                            event);
                    return JsonUtil.toSafeJson(event);
                });

        Flux<String> finalEvent = Flux.defer(() -> {
            String content = answer.toString();
            ChatMessageEntity entity = saveBuiltinAgentMessage(
                    conversationId, ownerJiacn, ownerClientId, generation, content);
            Map<String, Object> event = buildAgentEvent("agent_message", conversationId, content, entity.getId());
            String eventJson = JsonUtil.toSafeJson(event);
            chatConversationEventBroker.publishIfLive(
                    conversationId, generation,
                    () -> chatConversationService.isLiveGeneration(
                            ownerJiacn, ownerClientId, conversationId, generation),
                    event);
            return Flux.just(eventJson);
        });
        Flux<String> agentStream = deltaStream.concatWith(finalEvent)
                .onErrorResume(error -> {
                    log.warn("Builtin SongJiang stream failed, using fallback reply", error);
                    String content = "诸位稍安，宋江已收到传令。此事先记入议程，待诸位好汉回报后再作定夺。";
                    if (needSummary) {
                        summary.append(content);
                    }
                    ChatMessageEntity entity = saveBuiltinAgentMessage(
                            conversationId, ownerJiacn, ownerClientId, generation, content);
                    Map<String, Object> event = buildAgentEvent("agent_message", conversationId, content, entity.getId());
                    chatConversationEventBroker.publishIfLive(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation),
                            event);
                    return Flux.just(JsonUtil.toSafeJson(event));
                });
        return deliveryEvent.concatWith(agentStream);
    }

    private String buildAgentDeliveryEventJson(String conversationId, String agentId, boolean delivered) {
        return "{\"agentDelivery\":{\"agentId\":\"" + escapeJson(agentId)
                + "\",\"delivered\":" + delivered + "},\"conversationId\":\""
                + escapeJson(conversationId) + "\",\"conversationType\":\""
                + CONVERSATION_TYPE_JUYITING + "\"}";
    }

    private String buildAgentEventJson(String type, String conversationId, String content, Object messageId) {
        return JsonUtil.toSafeJson(buildAgentEvent(type, conversationId, content, messageId));
    }

    private Map<String, Object> buildAgentEvent(String type, String conversationId, String content, Object messageId) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("conversationId", conversationId);
        event.put("conversationType", CONVERSATION_TYPE_JUYITING);
        event.put("agentId", builtinHallAgentSupport.defaultAgentId());
        event.put("senderType", "agent");
        event.put("senderName", BuiltinHallAgentSupport.SONGJIANG_NAME);
        event.put("content", content);
        event.put("timestamp", System.currentTimeMillis());
        if (messageId != null) {
            event.put("messageId", ExactWireIds.decimal(messageId));
        }
        return event;
    }

    private ChatMessageEntity saveBuiltinAgentMessage(
            String conversationId, String ownerJiacn, String ownerClientId,
            long generation, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setJiacn(ownerJiacn);
        entity.setClientId(ownerClientId);
        entity.setConversationId(conversationId);
        entity.setMessageType("ASSISTANT");
        entity.setContent(content);
        entity.setSyncStatus("PENDING");
        entity.setConversationType(CONVERSATION_TYPE_JUYITING);
        entity.setSenderType("agent");
        entity.setSenderName(BuiltinHallAgentSupport.SONGJIANG_NAME);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agentId", builtinHallAgentSupport.defaultAgentId());
        metadata.put("senderType", "agent");
        metadata.put("senderName", BuiltinHallAgentSupport.SONGJIANG_NAME);
        metadata.put("conversationId", conversationId);
        metadata.put("conversationType", CONVERSATION_TYPE_JUYITING);
        entity.setMetadata(JsonUtil.toJson(metadata));
        return chatConversationService.appendOwnedMessage(
                ownerJiacn, ownerClientId, entity, generation);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String processContent(String content, boolean needSummary, StringBuilder summary) {
        if (needSummary) {
            summary.append(content);
        }
        return "{\"v\": \"" + StringEscapeUtils.escapeJson(content) + "\"}";
    }

    private Flux<String> handleSummary(
            boolean needSummary, String question, String answer, String conversationId,
            String ownerJiacn, String ownerClientId) {
        if (!needSummary || StringUtil.isBlank(answer)) {
            return Flux.empty();
        }
        String title = chatClientBuilder.build().prompt(
                        SUMMARY_PROMPT_TEMPLATE.create(Map.of("question", question, "answer", answer)))
                .call().content();
        chatConversationService.updateOwnedTitle(
                ownerJiacn, ownerClientId, conversationId, title, 1);
        return Flux.just("{\"t\": \"" + title + "\"}");
    }

    private String requireIdentityPart(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new AgentTaskThreadException(
                    AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Conversation identity is unavailable");
        }
        return value;
    }

    private long lifecycleGeneration(ChatConversationEntity conversation) {
        Long generation = conversation == null ? null : conversation.getLifecycleGeneration();
        if (generation == null) {
            return 1L;
        }
        if (generation < 1) {
            throw new AgentTaskThreadException(
                    AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Conversation generation is unavailable");
        }
        return generation;
    }

    @RequestMapping(value = "/stop_stream", method = RequestMethod.POST)
    public Object stopStream(@RequestBody ChatMessageDTO chatMessage) {
        requireGenericConversationAccess(chatMessage.getConversationId());
        redisService.publishSignal(chatMessage.getConversationId()).subscribe();
        return JsonResult.success();
    }

    @RequestMapping(value = "/conversation/delete", method = RequestMethod.DELETE)
    public Object deleteConversation(@RequestParam(name = "id") String id) {
        chatConversationService.deleteConversation(id);
        return JsonResult.success();
    }

    @RequestMapping(value = "/conversation/content", method = RequestMethod.GET)
    public Object getConversationContent(@RequestParam(name = "id") String id) {
        return JsonResult.success(chatConversationService.findByConversationId(id));
    }

    @RequestMapping(value = "/conversation/events", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> conversationEvents(@RequestParam(name = "id") String id) {
        ChatConversationEntity conversation = chatConversationService.get(id);
        long generation = lifecycleGeneration(conversation);
        String ownerJiacn = requireIdentityPart(EsContextHolder.getContext().getJiacn());
        String ownerClientId = requireIdentityPart(EsContextHolder.getContext().getClientId());
        return chatConversationEventBroker.stream(
                        id, generation, () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, id, generation))
                .map(event -> "data: " + event + "\n\n");
    }

    @RequestMapping(value = "/conversation/list", method = RequestMethod.POST)
    public Object listConversations(@RequestBody JsonRequestPage<ChatConversationEntity> page) {
        // conversationType can be passed directly in the search entity
        // e.g. {"search": {"conversationType": "juyiting"}}
        PageInfo<ChatConversationEntity> pageInfo = chatConversationService.findPage(
                page.getSearch(), 
                page.getPageNum(), 
                page.getPageSize(), 
                page.getOrderBy()
        );
        JsonResultPage<ChatConversationEntity> result = new JsonResultPage<>(pageInfo.getList());
        result.setPageNum(pageInfo.getPageNum());
        result.setTotal(pageInfo.getTotal());
        return result;
    }

    @RequestMapping(value = "/conversation/update", method = RequestMethod.POST)
    public Object updateConversation(@RequestBody ChatConversationEntity entity) {
        chatConversationService.update(entity);
        return JsonResult.success();
    }

    @RequestMapping(value = "/library/search", method = RequestMethod.POST)
    public Object searchLibrary(@RequestBody LibrarySearchRequest request) {
        if (request == null || StringUtil.isBlank(request.getKeyword())) {
            return JsonResult.success(List.of());
        }
        String jiacn = Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous");
        int topK = Optional.ofNullable(request.getTopK()).filter(value -> value > 0).orElse(8);
        double threshold = Optional.ofNullable(request.getSimilarityThreshold()).orElse(0.2D);
        List<MemoryDocument> documents;
        try {
            documents = taskThreadMemoryGuard.excludeProtected(
                    memoryRepository.searchWithConversationBoost(
                            jiacn,
                            request.getKeyword(),
                            request.getConversationId(),
                            topK,
                            threshold));
        } catch (Exception e) {
            log.warn("Library search failed, return empty results. jiacn={}, keyword={}", jiacn, request.getKeyword(), e);
            return JsonResult.success(List.of());
        }
        String sourceType = Optional.ofNullable(request.getSourceType()).orElse("");
        return JsonResult.success(documents.stream()
                .filter(document -> matchesLibrarySource(document, sourceType))
                .map(this::toLibrarySearchResult)
                .toList());
    }

    @RequestMapping(value = "/library/documents", method = RequestMethod.POST)
    public Object saveLibraryDocument(@RequestBody LibraryDocumentRequest request) {
        if (request == null || StringUtil.isBlank(request.getContent())) {
            return JsonResult.failure("LIBRARY_DOCUMENT_EMPTY", "library document content is required");
        }
        String jiacn = Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous");
        String title = Optional.ofNullable(request.getTitle()).orElse("").trim();
        String content = StringUtil.isBlank(title)
                ? request.getContent()
                : title + "\n\n" + request.getContent();

        MemoryDocument document = new MemoryDocument();
        document.setJiacn(jiacn);
        document.setConversationId(request.getConversationId());
        document.setContent(content);
        document.setSummaryType(Optional.ofNullable(request.getSourceType())
                .filter(StringUtil::isNotBlank)
                .orElse("project"));
        document.setTopic(Optional.ofNullable(request.getTopic()).orElse("project"));
        document.setCategories(Optional.ofNullable(request.getCategories()).orElse(List.of("project", "juyiting")));
        document.setTimestamp(System.currentTimeMillis());

        try {
            memoryRepository.save(document);
        } catch (Exception e) {
            log.warn("Library document save failed. jiacn={}, title={}", jiacn, title, e);
            return JsonResult.failure("LIBRARY_DOCUMENT_SAVE_FAILED", "library document save failed");
        }

        return JsonResult.success(Map.of(
                "title", title,
                "summaryType", document.getSummaryType(),
                "topic", document.getTopic(),
                "categories", document.getCategories(),
                "timestamp", document.getTimestamp()
        ));
    }

    private boolean matchesLibrarySource(MemoryDocument document, String sourceType) {
        if (document == null || StringUtil.isBlank(sourceType)) {
            return true;
        }
        String summaryType = Optional.ofNullable(document.getSummaryType()).orElse("");
        if ("meeting".equals(sourceType)) {
            return "conversation".equals(summaryType)
                    || "daily_summary".equals(summaryType)
                    || "weekly_summary".equals(summaryType)
                    || "monthly_summary".equals(summaryType);
        }
        if ("project".equals(sourceType)) {
            return "project".equals(summaryType)
                    || "project".equals(document.getTopic())
                    || Optional.ofNullable(document.getCategories()).orElse(List.of()).contains("project");
        }
        return "memory".equals(sourceType);
    }

    private LibrarySearchResult toLibrarySearchResult(MemoryDocument document) {
        LibrarySearchResult result = new LibrarySearchResult();
        result.setId(document.getId());
        result.setConversationId(document.getConversationId());
        result.setContent(document.getContent());
        result.setSummaryType(document.getSummaryType());
        result.setTopic(document.getTopic());
        result.setCategories(document.getCategories());
        result.setTimestamp(document.getTimestamp());
        result.setScore(document.getScore());
        return result;
    }

    @ExceptionHandler(AgentTaskThreadException.class)
    public ResponseEntity<JsonResult<Void>> handleReservedTaskThreadAccess() {
        JsonResult<Void> result = JsonResult.failure(
                "CONVERSATION_NOT_FOUND", "Conversation is not available");
        result.setStatus(HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
    }

    @lombok.Data
    private static class LibrarySearchRequest {
        private String keyword;
        private String sourceType;
        private String conversationId;
        private Integer topK;
        private Double similarityThreshold;
    }

    @lombok.Data
    private static class LibraryDocumentRequest {
        private String title;
        private String content;
        private String sourceType;
        private String topic;
        private List<String> categories;
        private String conversationId;
    }

    @lombok.Data
    private static class LibrarySearchResult {
        private String id;
        private String conversationId;
        private String content;
        private String summaryType;
        private String topic;
        private List<String> categories;
        private Long timestamp;
        private Double score;
    }
}
