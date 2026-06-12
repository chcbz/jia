package cn.jia.chat.api;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
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
import com.github.pagehelper.PageInfo;
import io.micrometer.core.instrument.util.StringEscapeUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

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
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final BuiltinHallAgentSupport builtinHallAgentSupport;
    private final ChatMessageDao chatMessageDao;
    private final MemoryRepository memoryRepository;
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

        Flux<String> cancelSignal = redisService.subscribeToChannel(conversationId);
        AgentDeliveryResult agentDelivery = relayDirectAgentMessage(chatMessage, conversationId, needSummary, summary);
        Flux<String> aiStream = agentDelivery.delivered()
                ? Flux.empty()
                : createAIStream(chatMessage, conversationId, needSummary, summary);

        return Flux.create(emitter -> {
            Flux<String> backendStream = agentDelivery.stream()
                    .concatWith(aiStream)
                    .takeUntilOther(cancelSignal)
                    .concatWith(Flux.defer(() -> Flux.just("{\"conversationId\": \"" + conversationId + "\", \"conversationType\": \"" + resolveConversationType(conversation) + "\"}")))
                    .concatWith(Flux.defer(() -> handleSummary(needSummary, chatMessage.getContent(), summary.toString(), conversationId)))
                    .doOnNext(emitter::next)
                    .doOnError(error -> {
                        log.error("Error processing chat response", error);
                        String errorMsg = error.getMessage() != null ? error.getMessage() : "Stream error";
                        String escapedMsg = errorMsg.replace("\\", "\\\\").replace("\"", "\\\"");
                        emitter.next("{\"error\": \"" + escapedMsg + "\", \"conversationId\": \"" + conversationId + "\", \"conversationType\": \"" + resolveConversationType(conversation) + "\"}");
                        emitter.complete();
                    })
                    .doOnComplete(emitter::complete)
                    .subscribeOn(Schedulers.boundedElastic());

            backendStream.subscribe();

            emitter.onDispose(() ->
                    log.debug("Client disconnected, backend processing continues for conversation: {}", conversationId)
            );
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private ChatConversationEntity getOrCreateConversation(ChatMessageDTO chatMessage) {
        ChatConversationEntity message;
        if (StringUtil.isBlank(chatMessage.getConversationId())) {
            message = new ChatConversationEntity();
            message.setStatus(0);
            message.setJiacn(EsContextHolder.getContext().getJiacn());
            message.setClientId(EsContextHolder.getContext().getClientId());
            message.setConversationType(
                    StringUtil.isNotBlank(chatMessage.getConversationType())
                            ? chatMessage.getConversationType()
                            : CONVERSATION_TYPE_NORMAL
            );
            message = chatConversationService.create(message);
        } else {
            message = chatConversationService.get(chatMessage.getConversationId());
        }
        return message;
    }

    private String resolveConversationTypeStr(ChatMessageDTO chatMessage) {
        if (chatMessage == null || StringUtil.isBlank(chatMessage.getConversationType())) {
            return CONVERSATION_TYPE_NORMAL;
        }
        return chatMessage.getConversationType();
    }
    private String resolveConversationType(ChatConversationEntity conversation) {
        if (conversation == null) {
            return CONVERSATION_TYPE_NORMAL;
        }
        return StringUtil.isNotBlank(conversation.getConversationType())
                ? conversation.getConversationType()
                : CONVERSATION_TYPE_NORMAL;
    }

    private Flux<String> createAIStream(ChatMessageDTO chatMessage, String conversationId, boolean needSummary, StringBuilder summary) {
        String jiacn = Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous");
        String clientId = Optional.ofNullable(EsContextHolder.getContext().getClientId()).orElse("jia_client");
        String filterExpression = "metadata.jiacn == '" + jiacn + "' AND role == 'ASSISTANT'";
        
        return chatClient.prompt(
                        Prompt.builder().messages(UserMessage.builder().text(chatMessage.getContent()).build()).build())
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("jiacn", jiacn)
                        .param("clientId", clientId)
                        .param("conversationType", resolveConversationTypeStr(chatMessage))
                        .param("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse(""))
                        .param("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse(""))
                        .param("selectedAgentId", Optional.ofNullable(selectedAgentId(chatMessage)).orElse(""))
                        .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filterExpression))
                .messages()
                .stream().content()
                .map(content -> processContent(content, needSummary, summary));
    }

    private AgentDeliveryResult relayDirectAgentMessage(ChatMessageDTO chatMessage, String conversationId, boolean needSummary, StringBuilder summary) {
        String selectedAgentId = selectedAgentId(chatMessage);
        if (!CONVERSATION_TYPE_JUYITING.equals(resolveConversationTypeStr(chatMessage))
                || StringUtil.isBlank(selectedAgentId)) {
            return new AgentDeliveryResult(false, false, Flux.empty());
        }

        saveDirectUserMessage(chatMessage, conversationId);

        if (builtinHallAgentSupport.isBuiltinAgent(selectedAgentId)) {
            return new AgentDeliveryResult(true, true,
                    createBuiltinSongJiangStream(chatMessage, conversationId, needSummary, summary));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("conversationType", CONVERSATION_TYPE_JUYITING);
        payload.put("agentId", selectedAgentId);
        payload.put("content", chatMessage.getContent());
        payload.put("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        payload.put("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));
        payload.put("metadata", Optional.ofNullable(chatMessage.getMetadata()).orElse(Map.of()));
        payload.put("timestamp", System.currentTimeMillis());

        boolean delivered = agentWebSocketHandler.isAgentConnected(selectedAgentId);
        Flux<String> stream = delivered
                ? Flux.create(emitter -> {
                    final Disposable[] subscriptionRef = new Disposable[1];
                    Disposable disposable = chatConversationEventBroker.stream(conversationId)
                            .filter(this::isDirectAgentEvent)
                            .subscribe(eventJson -> {
                                emitter.next(eventJson);
                                if (isDirectAgentFinalEvent(eventJson)) {
                                    Disposable current = subscriptionRef[0];
                                    if (current != null && !current.isDisposed()) {
                                        current.dispose();
                                    }
                                    emitter.complete();
                                }
                            }, emitter::error);
                    subscriptionRef[0] = disposable;

                    boolean sent = agentWebSocketHandler.sendDirectMessageToAgent(selectedAgentId, payload);
                    emitter.next("{\"agentDelivery\":{\"agentId\":\"" + escapeJson(selectedAgentId)
                            + "\",\"delivered\":" + sent + "},\"conversationId\":\""
                            + escapeJson(conversationId) + "\",\"conversationType\":\""
                            + CONVERSATION_TYPE_JUYITING + "\"}");

                    if (!sent) {
                        disposable.dispose();
                        emitter.complete();
                    }

                    emitter.onDispose(disposable);
                }, FluxSink.OverflowStrategy.BUFFER)
                : Flux.just("{\"agentDelivery\":{\"agentId\":\"" + escapeJson(selectedAgentId)
                        + "\",\"delivered\":false},\"conversationId\":\""
                        + escapeJson(conversationId) + "\",\"conversationType\":\""
                        + CONVERSATION_TYPE_JUYITING + "\"}");
        return new AgentDeliveryResult(true, delivered, stream);
    }

    private Flux<String> createBuiltinSongJiangStream(ChatMessageDTO chatMessage, String conversationId, boolean needSummary, StringBuilder summary) {
        StringBuilder answer = new StringBuilder();
        Flux<String> deliveryEvent = Flux.just(buildAgentDeliveryEventJson(conversationId, builtinHallAgentSupport.defaultAgentId(), true));

        Flux<String> deltaStream = chatClient.prompt(
                        Prompt.builder().messages(UserMessage.builder().text(chatMessage.getContent()).build()).build())
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("jiacn", Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous"))
                        .param("clientId", Optional.ofNullable(EsContextHolder.getContext().getClientId()).orElse("jia_client"))
                        .param("conversationType", CONVERSATION_TYPE_JUYITING)
                        .param("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse("user"))
                        .param("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"))
                        .param("selectedAgentId", builtinHallAgentSupport.defaultAgentId())
                        .param(QuestionAnswerAdvisor.FILTER_EXPRESSION,
                                "metadata.jiacn == '" + Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous") + "' AND role == 'ASSISTANT'"))
                .messages()
                .stream().content()
                .map(chunk -> {
                    if (needSummary) {
                        summary.append(chunk);
                    }
                    answer.append(chunk);
                    Map<String, Object> event = buildAgentEvent("agent_message_delta", conversationId, chunk, null);
                    chatConversationEventBroker.publish(conversationId, event);
                    return JsonUtil.toJson(event);
                });

        Flux<String> finalEvent = Flux.defer(() -> {
            String content = answer.toString();
            ChatMessageEntity entity = saveBuiltinAgentMessage(conversationId, content);
            Map<String, Object> event = buildAgentEvent("agent_message", conversationId, content, entity.getId());
            String eventJson = JsonUtil.toJson(event);
            chatConversationEventBroker.publish(conversationId, event);
            return Flux.just(eventJson);
        });
        Flux<String> agentStream = deltaStream.concatWith(finalEvent)
                .onErrorResume(error -> {
                    log.warn("Builtin SongJiang stream failed, using fallback reply", error);
                    String content = "诸位稍安，宋江已收到传令。此事先记入议程，待诸位好汉回报后再作定夺。";
                    if (needSummary) {
                        summary.append(content);
                    }
                    ChatMessageEntity entity = saveBuiltinAgentMessage(conversationId, content);
                    Map<String, Object> event = buildAgentEvent("agent_message", conversationId, content, entity.getId());
                    chatConversationEventBroker.publish(conversationId, event);
                    return Flux.just(JsonUtil.toJson(event));
                });
        return deliveryEvent.concatWith(agentStream);
    }

    private boolean isDirectAgentEvent(String eventJson) {
        if (StringUtil.isBlank(eventJson)) {
            return false;
        }
        return eventJson.contains("\"type\":\"agent_message_delta\"")
                || eventJson.contains("\"type\":\"agent_message\"");
    }

    private boolean isDirectAgentFinalEvent(String eventJson) {
        return StringUtil.isNotBlank(eventJson) && eventJson.contains("\"type\":\"agent_message\"");
    }

    private String buildAgentDeliveryEventJson(String conversationId, String agentId, boolean delivered) {
        return "{\"agentDelivery\":{\"agentId\":\"" + escapeJson(agentId)
                + "\",\"delivered\":" + delivered + "},\"conversationId\":\""
                + escapeJson(conversationId) + "\",\"conversationType\":\""
                + CONVERSATION_TYPE_JUYITING + "\"}";
    }

    private String buildAgentEventJson(String type, String conversationId, String content, Object messageId) {
        return JsonUtil.toJson(buildAgentEvent(type, conversationId, content, messageId));
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
            event.put("messageId", messageId);
        }
        return event;
    }

    private ChatMessageEntity saveBuiltinAgentMessage(String conversationId, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.init4Creation();
        entity.setJiacn(Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous"));
        entity.setClientId(Optional.ofNullable(EsContextHolder.getContext().getClientId()).orElse("jia_client"));
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
        chatMessageDao.insert(entity);
        return entity;
    }

    private void saveDirectUserMessage(ChatMessageDTO chatMessage, String conversationId) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.init4Creation();
        entity.setJiacn(Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous"));
        entity.setClientId(Optional.ofNullable(EsContextHolder.getContext().getClientId()).orElse("jia_client"));
        entity.setConversationId(conversationId);
        entity.setMessageType("USER");
        entity.setContent(chatMessage.getContent());
        entity.setSyncStatus("PENDING");
        entity.setConversationType(CONVERSATION_TYPE_JUYITING);
        entity.setSenderType(Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        entity.setSenderName(Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("conversationId", conversationId);
        metadata.put("conversationType", CONVERSATION_TYPE_JUYITING);
        metadata.put("selectedAgentId", selectedAgentId(chatMessage));
        if (chatMessage.getMetadata() != null) {
            metadata.putAll(chatMessage.getMetadata());
        }
        entity.setMetadata(JsonUtil.toJson(metadata));
        chatMessageDao.insert(entity);
    }

    private String selectedAgentId(ChatMessageDTO chatMessage) {
        if (chatMessage == null) {
            return null;
        }
        if (chatMessage.getMetadata() != null) {
            Object value = chatMessage.getMetadata().get("selectedAgentId");
            if (value != null && StringUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        if (CONVERSATION_TYPE_JUYITING.equals(resolveConversationTypeStr(chatMessage))) {
            return builtinHallAgentSupport.defaultAgentId();
        }
        return null;
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

    private Flux<String> handleSummary(boolean needSummary, String question, String answer, String conversationId) {
        if (!needSummary) {
            return Flux.empty();
        }
        String title = chatClientBuilder.build().prompt(
                        SUMMARY_PROMPT_TEMPLATE.create(Map.of("question", question, "answer", answer)))
                .call().content();
        ChatConversationEntity upMessage = new ChatConversationEntity();
        upMessage.setId(Long.valueOf(conversationId));
        upMessage.setTitle(title);
        upMessage.setStatus(1);
        chatConversationService.update(upMessage);
        return Flux.just("{\"t\": \"" + title + "\"}");
    }

    @RequestMapping(value = "/stop_stream", method = RequestMethod.POST)
    public Object stopStream(@RequestBody ChatMessageDTO chatMessage) {
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
        return chatConversationEventBroker.stream(id)
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
        List<MemoryDocument> documents = memoryRepository.searchWithConversationBoost(
                jiacn,
                request.getKeyword(),
                request.getConversationId(),
                topK,
                threshold
        );
        String sourceType = Optional.ofNullable(request.getSourceType()).orElse("");
        return JsonResult.success(documents.stream()
                .filter(document -> matchesLibrarySource(document, sourceType))
                .map(this::toLibrarySearchResult)
                .toList());
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

    private record AgentDeliveryResult(boolean attempted, boolean delivered, Flux<String> stream) {
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
