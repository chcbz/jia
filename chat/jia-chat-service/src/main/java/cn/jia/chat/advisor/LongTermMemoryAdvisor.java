package cn.jia.chat.advisor;

import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.ConversationAdmissionService;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.core.tenant.UserAdmissionScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Injects only SQL-visible, tenant-admitted long-term memory into a chat request. */
@Slf4j
public class LongTermMemoryAdvisor implements BaseAdvisor {
    private static final String MEMORY_CONTEXT_KEY = "long_term_memory_context";
    private static final int MAX_MEMORY_TOKENS = 2000;
    private static final int CHARS_PER_TOKEN = 4;

    private final MemoryRepository memoryRepository;
    private final ConversationAdmissionService admissionService;
    private final boolean memoryEnabled;
    private final int memoryTopK;
    private final double similarityThreshold;
    private final String defaultSystemPrompt;
    private final AgentTaskThreadMemoryGuard taskThreadMemoryGuard;

    private LongTermMemoryAdvisor(Builder builder) {
        this.memoryRepository = builder.memoryRepository;
        this.admissionService = builder.admissionService;
        this.memoryEnabled = builder.memoryEnabled;
        this.memoryTopK = builder.memoryTopK;
        this.similarityThreshold = builder.similarityThreshold;
        this.defaultSystemPrompt = builder.defaultSystemPrompt;
        this.taskThreadMemoryGuard = builder.taskThreadMemoryGuard;
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
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        if (!memoryEnabled) return request;
        String conversationId = getConversationId(request.context(), "default");
        try {
            String userMessage = Optional.of(request.prompt().getUserMessage())
                    .map(Message::getText).orElse("");
            Object scopeValue = request.context().get(
                    DatabaseChatMemoryAdvisor.USER_ADMISSION_SCOPE);
            if (!(scopeValue instanceof UserAdmissionScope admission)) {
                throw new IllegalStateException("Admitted conversation scope is unavailable");
            }
            List<MemoryDocument> found = admissionService.read(admission,
                    scope -> memoryRepository.searchWithConversationBoost(
                            scope, userMessage, conversationId,
                            memoryTopK, similarityThreshold));
            List<MemoryDocument> memories = taskThreadMemoryGuard.excludeProtected(found);
            if (memories == null || memories.isEmpty()) return request;

            String memoryContext = formatMemoryContext(memories);
            SystemMessage existingSystemMessage = request.prompt().getSystemMessage();
            String existingSystemText = Optional.ofNullable(existingSystemMessage)
                    .map(SystemMessage::getText).orElse(defaultSystemPrompt);
            Map<String, Object> updatedContext = new java.util.HashMap<>(request.context());
            updatedContext.put(MEMORY_CONTEXT_KEY, memoryContext);
            return request.mutate()
                    .context(updatedContext)
                    .prompt(request.prompt().augmentSystemMessage(
                            existingSystemText + "\n\n" + memoryContext))
                    .build();
        } catch (Exception failure) {
            log.error("Failed to retrieve admitted long-term memory", failure);
            return request;
        }
    }

    @Override
    public ChatClientResponse after(
            ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public reactor.core.publisher.Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request,
            org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain chain) {
        return reactor.core.publisher.Mono.just(request).flatMapMany(chain::nextStream);
    }

    private String formatMemoryContext(List<MemoryDocument> memories) {
        StringBuilder context = new StringBuilder("以下是与当前对话相关的历史记忆：\n\n");
        int totalChars = context.length();
        int maxChars = MAX_MEMORY_TOKENS * CHARS_PER_TOKEN;
        for (int i = 0; i < memories.size(); i++) {
            MemoryDocument memory = memories.get(i);
            String text = String.format("【记忆 %d】(%s)\n%s\n\n",
                    i + 1, memory.getSummaryType(), memory.getContent());
            if (totalChars + text.length() > maxChars) break;
            context.append(text);
            totalChars += text.length();
        }
        return context.append("请结合以上记忆信息回答用户的问题。").toString();
    }

    private String getConversationId(Map<String, Object> context, String defaultValue) {
        Object conversationId = context.get("conversationId");
        if (conversationId == null) {
            conversationId = context.get("chatMemory_conversationId");
        }
        return conversationId == null ? defaultValue : String.valueOf(conversationId);
    }

    public static class Builder {
        private final MemoryRepository memoryRepository;
        private ConversationAdmissionService admissionService;
        private boolean memoryEnabled = true;
        private int memoryTopK = 5;
        private double similarityThreshold = 0.75;
        private String defaultSystemPrompt = "你是一个有帮助的AI助手。";
        private AgentTaskThreadMemoryGuard taskThreadMemoryGuard;

        public Builder(MemoryRepository memoryRepository) {
            Assert.notNull(memoryRepository, "memoryRepository cannot be null");
            this.memoryRepository = memoryRepository;
        }

        public Builder admissionService(ConversationAdmissionService admissionService) {
            this.admissionService = admissionService;
            return this;
        }

        public Builder taskThreadMemoryGuard(
                AgentTaskThreadMemoryGuard taskThreadMemoryGuard) {
            this.taskThreadMemoryGuard = taskThreadMemoryGuard;
            return this;
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
            Assert.notNull(admissionService, "admissionService cannot be null");
            Assert.notNull(taskThreadMemoryGuard, "taskThreadMemoryGuard cannot be null");
            return new LongTermMemoryAdvisor(this);
        }
    }
}
