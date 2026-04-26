package cn.jia.chat.advisor;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.util.Assert;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 基于数据库的聊天记忆 Advisor
 * <p>
 * 参考 CustomerVectorStoreChatMemoryAdvisor 实现，解决 DatabaseChatMemoryRepository#saveAll 导致的重复插入问题。
 * <p>
 * 工作原理：
 * 1. before: 查询历史消息增强上下文，并保存用户消息到数据库
 * 2. after: 保存 AI 响应消息到数据库
 * <p>
 * 与 CustomerVectorStoreChatMemoryAdvisor 的区别：
 * - 数据存储在数据库而非向量数据库
 * - 避免了 Spring AI ChatMemory 的 saveAll 重复调用问题
 * 
 * @author lingma
 * @version 2.2
 */
@Slf4j
public class DatabaseChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    public static final String USER_MESSAGE = "user_message";

    private static final String MEMORY_TEMPLATE = """
        {instructions}

        Use the conversation memory from the MEMORY section to provide accurate answers.

        ---------------------
        MEMORY:
        {memory}
        ---------------------
        """;

    private final PromptTemplate systemPromptTemplate;

    private final String defaultConversationId;

    private final int order;

    private final ChatMessageDao chatMessageDao;

    private final int maxMessages;

    private final Scheduler scheduler;

    private DatabaseChatMemoryAdvisor(PromptTemplate systemPromptTemplate, String defaultConversationId,
                                       int order, ChatMessageDao chatMessageDao, int maxMessages, Scheduler scheduler) {
        Assert.notNull(systemPromptTemplate, "systemPromptTemplate cannot be null");
        Assert.hasText(defaultConversationId, "defaultConversationId cannot be null or empty");
        Assert.notNull(chatMessageDao, "chatMessageDao cannot be null");
        Assert.isTrue(maxMessages > 0, "maxMessages must be greater than 0");
        this.systemPromptTemplate = systemPromptTemplate;
        this.defaultConversationId = defaultConversationId;
        this.order = order;
        this.chatMessageDao = chatMessageDao;
        this.maxMessages = maxMessages;
        this.scheduler = scheduler;
    }

    public static Builder builder(ChatMessageDao chatMessageDao) {
        return new Builder(chatMessageDao);
    }

    @Override
    public String getName() {
        return "DatabaseChatMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String conversationId = getConversationId(request.context(), this.defaultConversationId);
        
        // 获取用户消息
        String userMessageText = Optional.of(request.prompt().getUserMessage())
                .map(AbstractMessage::getText).orElse("");
        
        // 查询最近 maxMessages 条历史消息用于增强上下文
        List<ChatMessageEntity> historyMessages = chatMessageDao.findByConversationIdWithLimit(conversationId, maxMessages);

        // 构建内存文本
        String memory = historyMessages.stream()
                .sorted(Comparator.comparingLong(msg -> Optional.ofNullable(msg.getCreateTime()).orElse(0L)))
                .map(msg -> "[" + msg.getMessageType() + "] " + msg.getContent())
                .reduce("", (a, b) -> a + (a.isEmpty() ? b : "\n" + b));
        
        // 增强系统提示词
        SystemMessage systemMessage = request.prompt().getSystemMessage();
        String augmentedSystemText = this.systemPromptTemplate
                .render(Map.of(
                        "instructions", Optional.ofNullable(systemMessage.getText()).orElse(""),
                        "memory", memory.isEmpty() ? "(No previous conversation)" : memory
                ));

        // 保存用户消息到数据库
        saveMessage(request.prompt().getUserMessage(), request.context());

        // 更新上下文中的用户消息
        return request.mutate()
                .context(Map.of(USER_MESSAGE, userMessageText))
                .prompt(request.prompt().augmentSystemMessage(augmentedSystemText))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        List<Message> assistantMessages = new ArrayList<>();
        if (chatClientResponse.chatResponse() != null) {
            assistantMessages = chatClientResponse.chatResponse()
                    .getResults()
                    .stream()
                    .map(g -> (Message) g.getOutput())
                    .toList();
        }
        
        // 保存 AI 响应消息到数据库
        for (Message message : assistantMessages) {
            if (message instanceof AssistantMessage assistantMessage
                    && StringUtil.isNotBlank(assistantMessage.getText())) {
                saveMessage(assistantMessage, chatClientResponse.context());
            }
        }
        
        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        // Get the scheduler from BaseAdvisor
        Scheduler scheduler = this.getScheduler();

        // Process the request with the before method
        return Mono.just(chatClientRequest)
                .publishOn(scheduler)
                .map(request -> this.before(request, streamAdvisorChain))
                .flatMapMany(streamAdvisorChain::nextStream)
                .transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
                        response -> this.after(response, streamAdvisorChain)));
    }

    /**
     * 保存消息到数据库
     */
    private void saveMessage(Message message, Map<String, Object> context) {
        String conversationId = getConversationId(context, defaultConversationId);
        try {
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.init4Creation();
            entity.setJiacn(String.valueOf(context.get("jiacn")));
            entity.setClientId(String.valueOf(context.get("clientId")));
            entity.setConversationId(conversationId);
            entity.setMessageType(message.getMessageType().name());
            entity.setContent(message.getText());

            Map<String, Object> metadata = new HashMap<>(context);
            Optional.of(message.getMetadata()).ifPresent(metadata::putAll);
            entity.setMetadata(JsonUtil.toJson(metadata));

            chatMessageDao.insert(entity);
            log.debug("Saved message to conversation: {}", conversationId);
        } catch (Exception e) {
            log.error("Failed to save message for conversation ID: {}", conversationId, e);
            throw new RuntimeException("Failed to save message for conversation ID: " + conversationId, e);
        }
    }

    /**
     * Builder for DatabaseChatMemoryAdvisor
     */
    public static class Builder {

        private PromptTemplate systemPromptTemplate = new PromptTemplate(MEMORY_TEMPLATE);

        private String conversationId = "default";

        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

        private final ChatMessageDao chatMessageDao;

        private int maxMessages = 20;

        private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

        /**
         * Creates a new builder instance.
         * @param chatMessageDao the chat message DAO
         */
        protected Builder(ChatMessageDao chatMessageDao) {
            this.chatMessageDao = chatMessageDao;
        }

        /**
         * Set the system prompt template.
         * @param systemPromptTemplate the system prompt template
         * @return this builder
         */
        public Builder systemPromptTemplate(PromptTemplate systemPromptTemplate) {
            this.systemPromptTemplate = systemPromptTemplate;
            return this;
        }

        /**
         * Set the max messages to retrieve.
         * @param maxMessages the max messages
         * @return this builder
         */
        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        /**
         * Set the conversation id.
         * @param conversationId the conversation id
         * @return the builder
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        /**
         * Set the order.
         * @param order the order
         * @return the builder
         */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        /**
         * Set the scheduler.
         * @param scheduler the scheduler
         * @return this builder
         */
        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Build the advisor.
         * @return the advisor
         */
        public DatabaseChatMemoryAdvisor build() {
            return new DatabaseChatMemoryAdvisor(this.systemPromptTemplate, this.conversationId,
                    this.order, this.chatMessageDao, this.maxMessages, this.scheduler);
        }
    }
}
