package cn.jia.chat.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 记忆存储库实现类 (Elasticsearch)
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
@Slf4j
@Lazy
@Repository
public class MemoryRepositoryImpl implements MemoryRepository {

    @Autowired
    private ElasticsearchClient elasticsearchClient;
    
    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard taskThreadMemoryGuard;

    @Value("${chat.memory.vector-index:chat_memory}")
    private String indexName;
    
    @Value("${chat.memory.embedding.dimension:1024}")
    private int embeddingDimension;

    @Value("${chat.memory.retrieval.current-conversation-weight:1.5}")
    private double currentConversationWeight;

    @Value("${chat.memory.retrieval.same-day-weight:1.2}")
    private double sameDayWeight;

    @Override
    public void save(MemoryDocument document) {
        if (document == null) {
            return;
        }
        requireOwner(document.getJiacn());
        // Public write paths always generate their own vector, ignoring any caller-supplied vector.
        document.setEmbedding(generateEmbeddingArray(document.getContent()));
        savePrepared(document);
    }

    @Override
    public void savePrepared(MemoryDocument document) {
        Objects.requireNonNull(document, "document");
        requireOwner(document.getJiacn());
        if (!StringUtils.hasText(document.getContent())) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }
        validateEmbedding(document.getEmbedding());
        try {
            if (!StringUtils.hasText(document.getId())) {
                document.setId(UUID.randomUUID().toString());
            }
            document.setTimestamp(document.getTimestamp() != null ? document.getTimestamp() : System.currentTimeMillis());

            Map<String, Object> docMap = convertToMap(document);

            IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> builder
                    .index(indexName)
                    .id(document.getId())
                    .document(docMap)
            );

            elasticsearchClient.index(request);
            log.debug("Saved memory document: {}", document.getId());
        } catch (Exception e) {
            log.error("Failed to save memory document", e);
            throw new RuntimeException("Failed to save memory document", e);
        }
    }

    @Override
    public void saveAll(List<MemoryDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (MemoryDocument doc : documents) {
            save(doc);
        }
    }

    @Override
    public List<MemoryDocument> searchWithConversationBoost(String jiacn, String userMessage,
            String currentConversationId, int topK, double similarityThreshold) {
        requireOwner(jiacn);
        try {
            LocalDate currentDay = LocalDate.now();
            long currentDayStart = currentDay.toEpochDay() * 24 * 60 * 60 * 1000L;


            // 生成嵌入向量（简化实现，实际应调用 embedding 服务）
            float[] queryVector = this.generateEmbedding(userMessage);

            // 使用 KNN 进行向量检索
            // 注意：Elasticsearch KNN 无法与 function_score 结合使用，因此权重在客户端应用
            SearchRequest searchRequest = SearchRequest.of(builder -> builder
                .index(indexName)
                .size(topK)
                .knn(k -> k
                    .filter(f -> f.term(t -> t.field("jiacn").value(jiacn)))
                    .field("embedding")
                    .queryVector(toFloatList(queryVector))
                    .k(topK)
                    .numCandidates(100)
                )
            );

            SearchResponse<Map> response = elasticsearchClient.search(searchRequest, Map.class);

            List<MemoryDocument> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source != null && jiacn.equals(source.get("jiacn"))) {
                    MemoryDocument doc = convertFromMap(source);
                    doc.setId(hit.id());
                    doc.setScore(hit.score());
                    
                    // 应用会话权重（需要在客户端处理，因为权重依赖文档字段值）
                    double weight = 1.0;
                    String docConversationId = (String) source.get("conversation_id");
                    Long timestamp = source.get("timestamp") instanceof Number 
                        ? ((Number) source.get("timestamp")).longValue() 
                        : null;

                    if (StringUtils.hasText(currentConversationId) 
                            && currentConversationId.equals(docConversationId)) {
                        weight = currentConversationWeight;
                    } else if (timestamp != null) {
                        long docDayStart = timestamp / (24 * 60 * 60 * 1000) * (24 * 60 * 60 * 1000);
                        if (docDayStart == currentDayStart) {
                            weight = sameDayWeight;
                        }
                    }

                    doc.setScore(doc.getScore() * weight);
                    results.add(doc);
                }
            }

            // 按加权分数排序并过滤低于阈值的记忆
            return results.stream()
                .sorted(Comparator.comparingDouble(MemoryDocument::getScore).reversed())
                .filter(m -> m.getScore() >= similarityThreshold)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to search with conversation boost", e);
            throw new RuntimeException("Failed to search memory", e);
        }
    }

    @Override
    public List<MemoryDocument> search(String jiacn, float[] queryVector, int topK) {
        requireOwner(jiacn);
        validateEmbedding(queryVector);
        try {
            SearchRequest searchRequest = SearchRequest.of(builder -> builder
                .index(indexName)
                .size(topK)
                .knn(k -> k
                    .filter(f -> f.term(t -> t.field("jiacn").value(jiacn)))
                    .field("embedding")
                    .queryVector(toFloatList(queryVector))
                    .k(topK)
                    .numCandidates(100)
                )
            );

            SearchResponse<Map> response = elasticsearchClient.search(searchRequest, Map.class);

            return response.hits().hits().stream()
                .filter(hit -> hit.source() != null && jiacn.equals(hit.source().get("jiacn")))
                .map(hit -> {
                    MemoryDocument doc = convertFromMap(hit.source());
                    doc.setId(hit.id());
                    doc.setScore(hit.score());
                    return doc;
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to search memory", e);
            throw new RuntimeException("Failed to search memory", e);
        }
    }

    @Override
    public List<String> findConversationSummariesByJiacn(String jiacn, long sinceTime) {
        return findSummariesByTypeAndJiacn(jiacn, "conversation", sinceTime);
    }

    @Override
    public List<String> findDailySummariesByJiacn(String jiacn, long sinceTime) {
        return findSummariesByTypeAndJiacn(jiacn, "daily_summary", sinceTime);
    }

    @Override
    public List<String> findWeeklySummariesByJiacn(String jiacn, long sinceTime) {
        return findSummariesByTypeAndJiacn(jiacn, "weekly_summary", sinceTime);
    }

    private List<String> findSummariesByTypeAndJiacn(String jiacn, String summaryType, long sinceTime) {
        requireOwner(jiacn);
        try {
            SearchRequest searchRequest = SearchRequest.of(builder -> builder
                .index(indexName)
                .size(100)
                .sort(s -> s.field(f -> f.field("timestamp").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                .query(q -> q
                    .bool(b -> b
                        .must(m -> m.term(t -> t.field("jiacn").value(jiacn)))
                        .must(m -> m.term(t -> t.field("summary_type").value(summaryType)))
                        .must(m -> m.range(r -> r.number(n -> n.field("timestamp").gte((double) sinceTime))))
                    )
                )
            );

            SearchResponse<Map> response = elasticsearchClient.search(searchRequest, Map.class);

            return response.hits().hits().stream()
                .filter(hit -> hit.source() != null
                        && jiacn.equals(hit.source().get("jiacn"))
                        && summaryType.equals(hit.source().get("summary_type")))
                .filter(hit -> !"conversation".equals(summaryType)
                        || !taskThreadMemoryGuard.isProtectedConversation(
                                (String) hit.source().get("conversation_id")))
                .map(hit -> (String) hit.source().get("content"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to find summaries", e);
            throw new IllegalStateException("Memory summaries unavailable", e);
        }
    }

    @Override
    public void deleteExpiredDocuments(long beforeTime) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(builder -> builder
                .index(indexName)
                .query(q -> q
                    .bool(b -> b
                        .must(m -> m.range(r -> r.number(n -> n.field("timestamp").lt((double) beforeTime))))
                        .must(m -> m.term(t -> t.field("summary_type").value("conversation")))
                    )
                )
            );

            elasticsearchClient.deleteByQuery(request);
            log.info("Deleted expired documents before {}", beforeTime);
        } catch (Exception e) {
            log.error("Failed to delete expired documents", e);
        }
    }

    @Override
    public void deleteOldDocuments(String summaryType, int keepCount) {
        try {
            // 查询要删除的文档
            SearchRequest searchRequest = SearchRequest.of(builder -> builder
                .index(indexName)
                .size(0)
                .aggregations("by_time", a -> a
                    .terms(t -> t.field("timestamp").size(10000))
                )
                .query(q -> q
                    .term(t -> t.field("summary_type").value(summaryType))
                )
            );

            // 这里简化处理，实际应该根据 timestamp 排序后删除超过 keepCount 的文档
            log.info("deleteOldDocuments called for type: {}, keepCount: {}", summaryType, keepCount);
        } catch (Exception e) {
            log.error("Failed to delete old documents", e);
        }
    }

    @Override
    public long countByJiacn(String jiacn) {
        requireOwner(jiacn);
        try {
            SearchRequest searchRequest = SearchRequest.of(builder -> builder
                .index(indexName)
                .size(0)
                .query(q -> q
                    .term(t -> t.field("jiacn").value(jiacn))
                )
            );

            SearchResponse<Map> response = elasticsearchClient.search(searchRequest, Map.class);
            return response.hits().total() != null ? response.hits().total().value() : 0;
        } catch (Exception e) {
            log.error("Failed to count documents", e);
            return 0;
        }
    }

    @Override
    public float[] generateEmbedding(String text) {
        return generateEmbeddingArray(text);
    }

    /**
     * 生成文本嵌入向量
     * 使用 Spring AI EmbeddingModel 生成真实的嵌入向量
     */
    private float[] generateEmbeddingArray(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }
        // A failed provider must not produce a successful write with random vectors.
        float[] embedding = embeddingModel.embed(text);
        validateEmbedding(embedding);
        return embedding;
    }

    private void requireOwner(String jiacn) {
        if (!StringUtils.hasText(jiacn)) {
            throw new IllegalArgumentException("Memory owner is required");
        }
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != embeddingDimension) {
            throw new IllegalStateException("Embedding dimension does not match memory index");
        }
        double norm = 0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Embedding contains non-finite values");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new IllegalStateException("Embedding must not be a zero vector");
        }
    }

    private List<Float> toFloatList(float[] floatArray) {
        List<Float> result = new ArrayList<>(floatArray.length);
        for (float v : floatArray) {
            result.add(v);
        }
        return result;
    }

    private List<Double> toDoubleList(float[] floatArray) {
        List<Double> result = new ArrayList<>(floatArray.length);
        for (float v : floatArray) {
            result.add((double) v);
        }
        return result;
    }

    private Map<String, Object> convertToMap(MemoryDocument doc) {
        Map<String, Object> map = new HashMap<>();
        if (doc.getId() != null) map.put("id", doc.getId());
        if (doc.getJiacn() != null) map.put("jiacn", doc.getJiacn());
        if (doc.getConversationId() != null) map.put("conversation_id", doc.getConversationId());
        if (doc.getContent() != null) map.put("content", doc.getContent());
        if (doc.getSummaryType() != null) map.put("summary_type", doc.getSummaryType());
        if (doc.getTopic() != null) map.put("topic", doc.getTopic());
        if (doc.getKeyPoints() != null) map.put("key_points", doc.getKeyPoints());
        if (doc.getCategories() != null) map.put("categories", doc.getCategories());
        if (doc.getPreferences() != null) map.put("preferences", doc.getPreferences());
        if (doc.getTimestamp() != null) map.put("timestamp", doc.getTimestamp());
        if (doc.getPeriodStart() != null) map.put("period_start", doc.getPeriodStart());
        if (doc.getPeriodEnd() != null) map.put("period_end", doc.getPeriodEnd());
        if (doc.getConversationCount() != null) map.put("conversation_count", doc.getConversationCount());
        if (doc.getEmbedding() != null) map.put("embedding", toDoubleList(doc.getEmbedding()));
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryDocument convertFromMap(Map<String, Object> map) {
        MemoryDocument doc = new MemoryDocument();
        if (map.containsKey("id")) doc.setId(String.valueOf(map.get("id")));
        if (map.containsKey("jiacn")) doc.setJiacn(String.valueOf(map.get("jiacn")));
        if (map.containsKey("conversation_id")) doc.setConversationId(String.valueOf(map.get("conversation_id")));
        if (map.containsKey("content")) doc.setContent(String.valueOf(map.get("content")));
        if (map.containsKey("summary_type")) doc.setSummaryType(String.valueOf(map.get("summary_type")));
        if (map.containsKey("topic")) doc.setTopic(String.valueOf(map.get("topic")));
        if (map.containsKey("key_points") && map.get("key_points") instanceof List) 
            doc.setKeyPoints((List<String>) map.get("key_points"));
        if (map.containsKey("categories") && map.get("categories") instanceof List) 
            doc.setCategories((List<String>) map.get("categories"));
        if (map.containsKey("preferences") && map.get("preferences") instanceof List) 
            doc.setPreferences((List<String>) map.get("preferences"));
        if (map.containsKey("timestamp") && map.get("timestamp") instanceof Number) 
            doc.setTimestamp(((Number) map.get("timestamp")).longValue());
        if (map.containsKey("period_start") && map.get("period_start") instanceof Number) 
            doc.setPeriodStart(((Number) map.get("period_start")).longValue());
        if (map.containsKey("period_end") && map.get("period_end") instanceof Number) 
            doc.setPeriodEnd(((Number) map.get("period_end")).longValue());
        if (map.containsKey("conversation_count") && map.get("conversation_count") instanceof Number) 
            doc.setConversationCount(((Number) map.get("conversation_count")).intValue());
        if (map.containsKey("embedding") && map.get("embedding") instanceof List) {
            List<Double> embList = (List<Double>) map.get("embedding");
            float[] emb = new float[embList.size()];
            for (int i = 0; i < embList.size(); i++) {
                emb[i] = embList.get(i).floatValue();
            }
            doc.setEmbedding(emb);
        }
        return doc;
    }
}