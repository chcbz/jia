package cn.jia.chat.advisor;

import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.Assert;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <p>
 * 长效记忆检索 Advisor
 * </p>
 * <p>
 * 负责在对话请求前检索用户的历史记忆，并将记忆内容注入到 AI 对话上下文中。
 * 使用 Elasticsearch 的 KNN + function_score 实现基于向量相似度和会话权重的检索。
 * </p>
 *
 * @author chc
 * @since 2026-05-02
 */
@Slf4j
public class LongTermMemoryAdvisor implements BaseAdvisor {

    private static final String MEMORY_CONTEXT_KEY = "long_term_memory_context";
    private static final int MAX_MEMORY_TOKENS = 2000;
    private static final int CHARS_PER_TOKEN = 4;  // 估算值

    private final MemoryRepository memoryRepository;
    private final boolean memoryEnabled;
    private final int memoryTopK;
    private final double similarityThreshold;
    private final String defaultSystemPrompt;

    private LongTermMemoryAdvisor(Builder builder) {
        this.memoryRepository = builder.memoryRepository;
        this.memoryEnabled = builder.memoryEnabled;
        this.memoryTopK = builder.memoryTopK;
        this.similarityThreshold = builder.similarityThreshold;
        this.defaultSystemPrompt = builder.defaultSystemPrompt;
    }

    public static Builder builder(MemoryRepository memoryRepository) {
        return new Builder(memoryRepository);
    }

    @Override
    public String getName() {
        return "LongTermMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        // 在 DatabaseChatMemoryAdvisor 之后执行
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        if (!memoryEnabled) {
            return request;
        }

        String conversationId = getConversationId(request.context(), "default");
        String jiacn = String.valueOf(request.context().getOrDefault("jiacn", "anonymous"));

        try {
            // 获取用户消息
            String userMessage = Optional.of(request.prompt().getUserMessage())
                    .map(Message::getText).orElse("");

            // 检索记忆（带会话权重）
            List<MemoryDocument> memories = memoryRepository.searchWithConversationBoost(
                    jiacn, userMessage, conversationId, memoryTopK, similarityThreshold);

            if (memories == null || memories.isEmpty()) {
                return request;
            }

            // 格式化为上下文文本
            String memoryContext = formatMemoryContext(memories);

            // 构建增强后的系统提示
            SystemMessage existingSystemMessage = request.prompt().getSystemMessage();
            String existingSystemText = Optional.of(existingSystemMessage)
                    .map(SystemMessage::getText).orElse(defaultSystemPrompt);

            String enhancedSystemText = existingSystemText + "\n\n" + memoryContext;

            // 更新上下文
            Map<String, Object> updatedContext = new java.util.HashMap<>(request.context());
            updatedContext.put(MEMORY_CONTEXT_KEY, memoryContext);

            return request.mutate()
                    .context(updatedContext)
                    .prompt(request.prompt().augmentSystemMessage(enhancedSystemText))
                    .build();

        } catch (Exception e) {
            log.error("Failed to retrieve long-term memory", e);
            return request;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public reactor.core.publisher.Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain streamAdvisorChain) {
        // 流式处理：将 before 处理后的请求传递给链
        return reactor.core.publisher.Mono.just(chatClientRequest)
                .flatMapMany(streamAdvisorChain::nextStream);
    }

    /**
     * 格式化为可读的上下文文本
     */
    private String formatMemoryContext(List<MemoryDocument> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("以下是与当前对话相关的历史记忆：\n\n");

        int totalChars = context.length();
        int maxChars = MAX_MEMORY_TOKENS * CHARS_PER_TOKEN;

        for (int i = 0; i < memories.size(); i++) {
            MemoryDocument memory = memories.get(i);
            String memoryText = String.format("【记忆 %d】(%s)\n%s\n\n",
                    i + 1,
                    memory.getSummaryType(),
                    memory.getContent());

            if (totalChars + memoryText.length() > maxChars) {
                break;
            }

            context.append(memoryText);
            totalChars += memoryText.length();
        }

        context.append("请结合以上记忆信息回答用户的问题。");
        return context.toString();
    }

    /**
     * 获取会话ID
     */
    private String getConversationId(Map<String, Object> context, String defaultValue) {
        Object conversationId = context.get("conversationId");
        if (conversationId == null) {
            conversationId = context.get("chatMemory_conversationId");  // ChatMemory.CONVERSATION_ID 常量值
        }
        return conversationId != null ? String.valueOf(conversationId) : defaultValue;
    }

    /**
     * Builder for LongTermMemoryAdvisor
     */
    public static class Builder {
        private final MemoryRepository memoryRepository;
        private boolean memoryEnabled = true;
        private int memoryTopK = 5;
        private double similarityThreshold = 0.75;
        private String defaultSystemPrompt = "你是一个有帮助的AI助手。";

        public Builder(MemoryRepository memoryRepository) {
            Assert.notNull(memoryRepository, "memoryRepository cannot be null");
            this.memoryRepository = memoryRepository;
        }

        public Builder memoryEnabled(boolean memoryEnabled) {
            this.memoryEnabled = memoryEnabled;
            return this;
        }

        public Builder memoryTopK(int memoryTopK) {
            this.memoryTopK = memoryTopK;
            return this;
        }

        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        public Builder defaultSystemPrompt(String defaultSystemPrompt) {
            this.defaultSystemPrompt = defaultSystemPrompt;
            return this;
        }

        public LongTermMemoryAdvisor build() {
            return new LongTermMemoryAdvisor(this);
        }
    }
}
