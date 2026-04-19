package cn.jia.agent.memory;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.DateUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ElasticsearchChatMemoryRepository implements ChatMemoryRepository {
    private final ElasticsearchClient elasticsearchClient;
    private String indexName;

    /**
     * 创建 Builder 实例
     * @return Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 类，用于构建 ElasticsearchChatMemoryRepository 实例
     */
    public static class Builder {
        private ElasticsearchClient elasticsearchClient;
        private String indexName;

        /**
         * 设置 Elasticsearch 客户端
         * @param elasticsearchClient Elasticsearch 客户端
         * @return Builder 对象（链式调用）
         */
        public Builder elasticsearchClient(ElasticsearchClient elasticsearchClient) {
            this.elasticsearchClient = elasticsearchClient;
            return this;
        }

        /**
         * 设置索引名称
         * @param indexName 索引名称
         * @return Builder 对象（链式调用）
         */
        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /**
         * 构建 ElasticsearchChatMemoryRepository 实例
         * @return ElasticsearchChatMemoryRepository 实例
         */
        public ElasticsearchChatMemoryRepository build() {
            Assert.notNull(elasticsearchClient, "elasticsearchClient must not be null");
            if (indexName != null && !indexName.trim().isEmpty()) {
                return new ElasticsearchChatMemoryRepository(elasticsearchClient, indexName);
            } else {
                return new ElasticsearchChatMemoryRepository(elasticsearchClient);
            }
        }
    }

    public ElasticsearchChatMemoryRepository(ElasticsearchClient elasticsearchClient) {
        Assert.notNull(elasticsearchClient, "elasticsearchClient must not be null");
        this.elasticsearchClient = elasticsearchClient;
    }
    
    public ElasticsearchChatMemoryRepository(ElasticsearchClient elasticsearchClient, String indexName) {
        Assert.notNull(elasticsearchClient, "elasticsearchClient must not be null");
        Assert.hasText(indexName, "indexName must not be empty");
        this.elasticsearchClient = elasticsearchClient;
        this.indexName = indexName;
    }

    @Override
    public List<String> findConversationIds() {
        return RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            try {
                String jiacn = EsContextHolder.getContext().getJiacn();
                
                // Build search request with aggregation to get unique conversationIds
                SearchRequest searchRequest = SearchRequest.of(builder -> builder
                    .index(indexName)
                    .size(0) // We only need aggregations
                    .query(q -> q
                        .term(t -> t
                            .field("metadata.jiacn.keyword")
                            .value(FieldValue.of(jiacn))
                        )
                    )
                    .aggregations("conversationIds", a -> a
                        .terms(t -> t
                            .field("metadata.conversationId.keyword")
                            .size(100)
                        )
                    )
                );
                
                SearchResponse<?> response = elasticsearchClient.search(searchRequest, Object.class);
                
                // Extract unique conversation IDs from aggregation
                List<String> conversationIds = new ArrayList<>();
                if (response.aggregations() != null && response.aggregations().containsKey("conversationIds")) {
                    var agg = response.aggregations().get("conversationIds");
                    if (agg.sterms() != null) {
                        for (var bucket : agg.sterms().buckets().array()) {
                            conversationIds.add(bucket.key().stringValue());
                        }
                    }
                }
                
                return conversationIds;
            } catch (Exception e) {
                throw new RuntimeException("Failed to find conversation IDs from Elasticsearch", e);
            }
        });
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            Assert.hasText(conversationId, "conversationId cannot be null or empty");
            try {
                String jiacn = EsContextHolder.getContext().getJiacn();
                
                // Build search request
                SearchRequest searchRequest = SearchRequest.of(builder -> builder
                    .index(indexName)
                    .size(100)
                    .source(s -> s.filter(f -> f.includes("id", "content", "metadata")))
                    .sort(s -> s
                        .field(f -> f
                            .field("metadata.timestamp")
                            .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)
                        )
                    )
                    .query(q -> q
                        .bool(b -> b
                            .must(term -> term
                                .term(t -> t
                                    .field("metadata.conversationId.keyword")
                                    .value(FieldValue.of(conversationId))
                                )
                            )
                            .must(jiacnTerm -> jiacnTerm
                                .term(t -> t
                                    .field("metadata.jiacn.keyword")
                                    .value(FieldValue.of(jiacn))
                                )
                            )
                        )
                    )
                );
                
                SearchResponse<Map> response = elasticsearchClient.search(searchRequest, Map.class);
                
                // Convert hits to Messages
                return response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = hit.source();
                        String text = (String) source.get("content");
                        Map<String, Object> metadata = (Map<String, Object>) source.get("metadata");
                        
                        UserMessage.Builder builder = UserMessage.builder()
                                .text(text)
                                .metadata(metadata);
                        return builder.build();
                    })
                    .collect(Collectors.toList());
            } catch (Exception e) {
                throw new RuntimeException("Failed to find messages by conversation ID: " + conversationId, e);
            }
        });
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            Assert.hasText(conversationId, "conversationId cannot be null or empty");
            Assert.notNull(messages, "messages cannot be null");
            Assert.noNullElements(messages, "messages cannot contain null elements");

            try {
                String jiacn = EsContextHolder.getContext().getJiacn();
                long timestamp = DateUtil.nowTime();
                
                for (Message msg : messages) {
                    // Generate unique ID for each message
                    String id = UUID.randomUUID().toString();
                    
                    // Build metadata
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("jiacn", jiacn);
                    metadata.put("messageType", msg.getMessageType().getValue());
                    metadata.put("conversationId", conversationId);
                    metadata.put("timestamp", timestamp);
                    
                    // Build document with id and metadata structure
                    Map<String, Object> document = new HashMap<>();
                    document.put("id", id);
                    document.put("content", msg.getText());
                    document.put("metadata", metadata);
                    
                    // Index the document
                    IndexRequest<Map> indexRequest = IndexRequest.of(builder -> builder
                        .index(indexName)
                        .id(id)
                        .document(document)
                    );
                    
                    elasticsearchClient.index(indexRequest);
                }
                
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to save messages for conversation ID: " + conversationId, e);
            }
        });
    }

    /**
     * 通过查询删除指定会话的消息
     * @param conversationId 会话 ID
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            Assert.hasText(conversationId, "conversationId cannot be null or empty");
            try {
                String jiacn = EsContextHolder.getContext().getJiacn();
                
                // Build delete by query request
                DeleteByQueryRequest deleteRequest = DeleteByQueryRequest.of(builder -> builder
                    .index(indexName)
                    .query(q -> q
                        .bool(b -> b
                            .must(convTerm -> convTerm
                                .term(t -> t
                                    .field("metadata.conversationId.keyword")
                                    .value(FieldValue.of(conversationId))
                                )
                            )
                            .must(jiacnTerm -> jiacnTerm
                                .term(t -> t
                                    .field("metadata.jiacn.keyword")
                                    .value(FieldValue.of(jiacn))
                                )
                            )
                        )
                    )
                );
                
                DeleteByQueryResponse response = elasticsearchClient.deleteByQuery(deleteRequest);
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete messages for conversation ID: " + conversationId, e);
            }
        });
    }
}
