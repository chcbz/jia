package cn.jia.ai.mcp.client.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.util.Assert;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;

import java.util.List;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

public class ElasticsearchChatMemoryRepository implements ChatMemoryRepository {

    private final ElasticsearchVectorStore vectorStore;

    public ElasticsearchChatMemoryRepository(ElasticsearchVectorStore vectorStore) {
        Assert.notNull(vectorStore, "VectorStore must not be null");
        this.vectorStore = vectorStore;
    }

    @Override
    @Retryable(value = IOException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public List<String> findConversationIds() {
        try {
            // Get all unique conversationIds from Elasticsearch
            return vectorStore.similaritySearch(
                SearchRequest.builder().query("*").topK(Integer.MAX_VALUE).build())
                .stream()
                .map(doc -> doc.getMetadata().get("conversationId").toString())
                .distinct()
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Failed to find conversation IDs from Elasticsearch", e);
        }
    }

    @Override
    @Retryable(value = IOException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        try {
            return getMessages(conversationId, Integer.MAX_VALUE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to find messages by conversation ID: " + conversationId, e);
        }
    }

    @Override
    @Retryable(value = IOException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        try {
            List<Document> docs = messages.stream()
                .map(msg -> new Document(msg.getText(),
                    Map.of(
                        "conversationId", conversationId,
                        "timestamp", System.currentTimeMillis(),
                        "role", msg.getMessageType().name()
                    )))
                .collect(Collectors.toList());

            vectorStore.add(docs);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save messages for conversation ID: " + conversationId, e);
        }
    }

    @Override
    @Retryable(value = IOException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        try {
            List<String> ids = vectorStore.similaritySearch(
                SearchRequest.builder().query("*")
                    .filterExpression("conversationId == '" + conversationId + "'")
                    .build())
                .stream()
                .map(Document::getId)
                .collect(Collectors.toList());
            if (!ids.isEmpty()) {
                vectorStore.delete(ids);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete messages for conversation ID: " + conversationId, e);
        }
    }

    private List<Message> getMessages(String conversationId, int limit) {
        List<Document> docs = vectorStore.similaritySearch(
            SearchRequest.builder().query("*")
                .topK(limit)
                .filterExpression("conversationId == '" + conversationId + "'")
                .build());

        return docs.stream()
            .map(doc -> {
                UserMessage.Builder builder = UserMessage.builder()
                    .text(doc.getText())
                    .metadata(doc.getMetadata());
                return builder.build();
            })
            .collect(Collectors.toList());
    }

    public static ElasticsearchChatMemoryRepositoryBuilder builder() {
        return new ElasticsearchChatMemoryRepositoryBuilder();
    }

    public static class ElasticsearchChatMemoryRepositoryBuilder {
        private ElasticsearchVectorStore vectorStore;

        public ElasticsearchChatMemoryRepositoryBuilder vectorStore(ElasticsearchVectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public ElasticsearchChatMemoryRepository build() {
            return new ElasticsearchChatMemoryRepository(vectorStore);
        }
    }
}
