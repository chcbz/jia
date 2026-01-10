package cn.jia.ai.mcp.client.advisor;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


public class CustomerVectorStoreChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    public static final String TOP_K = "chat_memory_vector_store_top_k";

    private static final String DOCUMENT_METADATA_CONVERSATION_ID = "conversationId";

    private static final String DOCUMENT_METADATA_MESSAGE_TYPE = "messageType";

    private static final String DOCUMENT_METADATA_TIMESTAMP = "timestamp";

    private static final String DOCUMENT_METADATA_USER_ID = "jiacn";

    private static final int DEFAULT_TOP_K = 20;

    private static final PromptTemplate DEFAULT_SYSTEM_PROMPT_TEMPLATE = new PromptTemplate("""
        {instructions}

        Use the conversation memory from the MEMORY section to provide accurate answers.

        ---------------------
        MEMORY:
        {memory}
        ---------------------
        """);

    private final PromptTemplate systemPromptTemplate;

    private final int defaultTopK;

    private final String defaultConversationId;

    private final int order;

    private final Scheduler scheduler;

    private final VectorStore vectorStore;

    private CustomerVectorStoreChatMemoryAdvisor(PromptTemplate systemPromptTemplate, int defaultTopK,
                                         String defaultConversationId, int order, Scheduler scheduler, VectorStore vectorStore) {
        Assert.notNull(systemPromptTemplate, "systemPromptTemplate cannot be null");
        Assert.isTrue(defaultTopK > 0, "topK must be greater than 0");
        Assert.hasText(defaultConversationId, "defaultConversationId cannot be null or empty");
        Assert.notNull(scheduler, "scheduler cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        this.systemPromptTemplate = systemPromptTemplate;
        this.defaultTopK = defaultTopK;
        this.defaultConversationId = defaultConversationId;
        this.order = order;
        this.scheduler = scheduler;
        this.vectorStore = vectorStore;
    }

    public static Builder builder(VectorStore chatMemory) {
        return new Builder(chatMemory);
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
        String query = request.prompt().getUserMessage() != null ? request.prompt().getUserMessage().getText() : "";
        int topK = getChatMemoryTopK(request.context());
        String filter = DOCUMENT_METADATA_CONVERSATION_ID + "=='" + conversationId + "'";
        filter += " AND " + DOCUMENT_METADATA_USER_ID + "=='" + request.context().get(DOCUMENT_METADATA_USER_ID) + "'";
        var searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filter)
                .build();
        List<Document> documents = this.vectorStore
                .similaritySearch(searchRequest);

        String memory = documents.stream()
                .sorted(Comparator.comparingLong(d ->
                        Optional.ofNullable(d.getMetadata().get(DOCUMENT_METADATA_TIMESTAMP))
                                .map(String::valueOf).map(Long::parseLong).orElse(0L)))
                .map(doc -> "[" + doc.getMetadata().get(DOCUMENT_METADATA_MESSAGE_TYPE) + "]" + doc.getText())
                .collect(Collectors.joining(System.lineSeparator()));

        SystemMessage systemMessage = request.prompt().getSystemMessage();
        String augmentedSystemText = this.systemPromptTemplate
                .render(Map.of("instructions", systemMessage.getText(), "memory", memory));

        ChatClientRequest processedChatClientRequest = request.mutate()
                .prompt(request.prompt().augmentSystemMessage(augmentedSystemText))
                .build();

        UserMessage userMessage = processedChatClientRequest.prompt()
                .getUserMessage();
        if (userMessage != null) {
            this.vectorStore.write(toDocuments(List.of(userMessage), conversationId, request.context()));
        }

        return processedChatClientRequest;
    }

    private int getChatMemoryTopK(Map<String, Object> context) {
        return context.containsKey(TOP_K) ? Integer.parseInt(context.get(TOP_K).toString()) : this.defaultTopK;
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
        this.vectorStore.write(toDocuments(assistantMessages,
                this.getConversationId(chatClientResponse.context(), this.defaultConversationId),
                chatClientResponse.context()));
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

    private List<Document> toDocuments(List<Message> messages, String conversationId, Map<String, Object> context) {
        return messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map(message -> {
                    var metadata = new HashMap<>(message.getMetadata() != null ? message.getMetadata() : new HashMap<>());
                    metadata.put(DOCUMENT_METADATA_CONVERSATION_ID, conversationId);
                    metadata.put(DOCUMENT_METADATA_MESSAGE_TYPE, message.getMessageType().name());
                    metadata.put(DOCUMENT_METADATA_TIMESTAMP, System.currentTimeMillis());
                    metadata.put(DOCUMENT_METADATA_USER_ID, context.get(DOCUMENT_METADATA_USER_ID));
                    if (message instanceof UserMessage userMessage) {
                        return Document.builder()
                                .text(userMessage.getText())
                                // userMessage.getMedia().get(0).getId()
                                // TODO vector store for memory would not store this into the
                                // vector store, could store an 'id' instead
                                // .media(userMessage.getMedia())
                                .metadata(metadata)
                                .build();
                    }
                    else if (message instanceof AssistantMessage assistantMessage) {
                        return Document.builder().text(assistantMessage.getText()).metadata(metadata).build();
                    }
                    throw new RuntimeException("Unknown message type: " + message.getMessageType());
                })
                .toList();
    }

    /**
     * Builder for 
     */
    public static class Builder {

        private PromptTemplate systemPromptTemplate = DEFAULT_SYSTEM_PROMPT_TEMPLATE;

        private Integer defaultTopK = DEFAULT_TOP_K;

        private String conversationId = ChatMemory.DEFAULT_CONVERSATION_ID;

        private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

        private final VectorStore vectorStore;

        /**
         * Creates a new builder instance.
         * @param vectorStore the vector store to use
         */
        protected Builder(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
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
         * Set the chat memory retrieve size.
         * @param defaultTopK the chat memory retrieve size
         * @return this builder
         */
        public Builder defaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
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

        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
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
         * Build the advisor.
         * @return the advisor
         */
        public CustomerVectorStoreChatMemoryAdvisor build() {
            return new CustomerVectorStoreChatMemoryAdvisor(this.systemPromptTemplate, this.defaultTopK, this.conversationId,
                    this.order, this.scheduler, this.vectorStore);
        }
    }
}
