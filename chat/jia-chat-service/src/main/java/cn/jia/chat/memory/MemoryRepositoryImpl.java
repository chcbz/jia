package cn.jia.chat.memory;

import cn.jia.chat.dao.MemoryPublicationDao;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.entity.MemoryPublicationPayload;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private static final List<String> PAYLOAD_FIELDS = List.of(
            "id", "jiacn", "tenant_id", "data_domain_id", "client_id", "scope_mode",
            "conversation_id", "document_kind", "publication_class", "state", "content",
            "summary_type", "timestamp", "topic", "key_points", "categories", "preferences",
            "period_start", "period_end", "conversation_count", "embedding",
            "snapshot_version", "snapshot_token", "lifecycle_generation");

    private static final Set<String> STRING_FIELDS = Set.of(
            "id", "jiacn", "tenant_id", "data_domain_id", "client_id", "scope_mode",
            "conversation_id", "document_kind", "publication_class", "state", "content",
            "summary_type", "topic", "snapshot_token");

    private static final Set<String> LONG_FIELDS = Set.of(
            "timestamp", "period_start", "period_end", "snapshot_version",
            "lifecycle_generation");

    private static final Set<String> STRING_LIST_FIELDS = Set.of(
            "key_points", "categories", "preferences");

    @Autowired
    private ElasticsearchClient elasticsearchClient;
    
    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private MemoryPublicationDao memoryPublicationDao;

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
        throw new IllegalStateException("Unscoped memory storage is disabled");
    }

    @Override
    public void save(ConversationDataScope scope, MemoryDocument document) {
        applyScope(scope, document);
        document.setDocumentKind(MemoryDocument.DOCUMENT_KIND_LIBRARY);
        document.setPublicationClass(MemoryDocument.PUBLICATION_CLASS_DIRECT);
        document.setState(MemoryDocument.STATE_PUBLISHED);
        indexDocument(document, null, null, true);
    }

    @Override
    public void saveConversationSnapshot(
            ConversationDataScope scope, MemoryDocument document,
            MemoryPublicationRecord publication) {
        applyScope(scope, document);
        if (!"conversation".equals(document.getSummaryType())) {
            throw new IllegalArgumentException(
                    "Versioned conversation memory requires conversation summary type");
        }
        requireExactIdentity(document.getConversationId(), "conversationId");
        Objects.requireNonNull(publication, "publication");
        if (publication.getEsRevision() == null || publication.getEsRevision() <= 0
                || publication.getMessageCount() == null || publication.getMessageCount() <= 0
                || !Objects.equals(publication.getTenantId(), scope.tenantId())
                || !Objects.equals(publication.getScopeMode(), scope.mode().name())
                || !Objects.equals(publication.getScopeKey(), scope.mode()
                        == ConversationDataScope.Mode.LEGACY_CLIENT
                        ? scope.callerClientId() : scope.dataDomainId())
                || !Objects.equals(publication.getOwnerJiacn(), scope.ownerJiacn())
                || !Objects.equals(publication.getConversationId(), document.getConversationId())
                || !Objects.equals(publication.getSnapshotToken(), document.getSnapshotToken())
                || !Objects.equals(publication.getLifecycleGeneration(), document.getLifecycleGeneration())) {
            throw new IllegalArgumentException("Publication does not match memory snapshot");
        }
        if (!("STAGED".equals(publication.getState())
                || "PUBLISHED".equals(publication.getState()))) {
            throw new IllegalArgumentException("Publication payload is not staged");
        }
        MemoryPublicationPayload payload = MemoryPublicationPayload.stored(
                publication.getSummaryContent(), publication.getEmbeddingBytes(),
                publication.getPayloadDigest());
        if (!Objects.equals(payload.summaryContent(), document.getContent())
                || !Arrays.equals(payload.embedding(), document.getEmbedding())
                || publication.getCreateTime() == null
                || !Objects.equals(publication.getCreateTime(), document.getTimestamp())) {
            throw new IllegalArgumentException(
                    "Memory document does not match the persisted publication payload");
        }
        document.setDocumentKind(MemoryDocument.DOCUMENT_KIND_CONVERSATION_SUMMARY);
        document.setPublicationClass(MemoryDocument.PUBLICATION_CLASS_MANIFEST);
        document.setState(MemoryDocument.STATE_PUBLISHED);
        String documentId = publication.getDocumentId();
        String expectedDocumentId = deterministicConversationDocumentId(
                scope, document.getConversationId(), document.getSnapshotToken());
        if (!Objects.equals(expectedDocumentId, documentId)) {
            throw new IllegalArgumentException(
                    "Publication document identity does not match memory snapshot");
        }
        document.setId(documentId);
        document.setSnapshotVersion(publication.getEsRevision());
        indexDocument(document, documentId, publication.getEsRevision(), false);
    }

    private void applyScope(ConversationDataScope scope, MemoryDocument document) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(document, "document");
        if (document.getJiacn() == null) {
            document.setJiacn(scope.ownerJiacn());
        } else if (!scope.ownerJiacn().equals(document.getJiacn())) {
            throw new IllegalArgumentException("Memory owner does not match admission scope");
        }
        document.setTenantId(scope.tenantId());
        document.setDataDomainId(scope.dataDomainId());
        document.setClientId(scope.callerClientId());
        document.setScopeMode(scope.mode().name());
    }

    private void indexDocument(
            MemoryDocument document, String documentId, Long snapshotVersion,
            boolean generateEmbedding) {
        try {
            if (generateEmbedding) {
                document.setEmbedding(generateEmbeddingArray(document.getContent()));
            } else if (document.getEmbedding() == null || document.getEmbedding().length == 0) {
                throw new IllegalStateException(
                        "Persisted memory publication embedding is unavailable");
            }
            document.setTimestamp(document.getTimestamp() != null
                    ? document.getTimestamp() : System.currentTimeMillis());
            Map<String, Object> docMap = convertToMap(document);
            String payloadFingerprint = canonicalPayloadFingerprint(docMap);
            document.setPayloadFingerprint(payloadFingerprint);
            docMap.put("payload_fingerprint", payloadFingerprint);

            IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> {
                builder.index(indexName).document(docMap);
                if (documentId != null && snapshotVersion != null) {
                    builder.id(documentId)
                            .version(snapshotVersion)
                            .versionType(VersionType.External);
                }
                return builder;
            });

            elasticsearchClient.index(request);
            log.debug("Saved memory document: {}", documentId);
        } catch (ElasticsearchException conflict) {
            if (documentId != null && snapshotVersion != null && conflict.status() == 409) {
                verifyExistingSnapshot(document, documentId, snapshotVersion);
                log.info("Verified idempotent memory snapshot retry: {}", documentId);
                return;
            }
            log.error("Failed to save memory document", conflict);
            throw new RuntimeException("Failed to save memory document", conflict);
        } catch (Exception e) {
            log.error("Failed to save memory document", e);
            throw new RuntimeException("Failed to save memory document", e);
        }
    }

    private void verifyExistingSnapshot(
            MemoryDocument expected, String documentId, long snapshotVersion) {
        final GetResponse<Map> existing;
        try {
            existing = elasticsearchClient.get(
                    request -> request.index(indexName).id(documentId), Map.class);
        } catch (Exception verificationFailure) {
            throw new IllegalStateException(
                    "Unable to verify conflicting memory snapshot", verificationFailure);
        }
        Map source = existing == null ? null : existing.source();
        String expectedFingerprint = canonicalPayloadFingerprint(convertToMap(expected));
        String actualFingerprint = source == null ? null : canonicalPayloadFingerprint(source);
        boolean exactScope = source != null
                && Objects.equals(expected.getTenantId(), source.get("tenant_id"))
                && Objects.equals(expected.getScopeMode(), source.get("scope_mode"))
                && Objects.equals(expected.getJiacn(), source.get("jiacn"))
                && Objects.equals(expected.getConversationId(), source.get("conversation_id"))
                && (ConversationDataScope.Mode.LEGACY_CLIENT.name().equals(expected.getScopeMode())
                ? Objects.equals(expected.getClientId(), source.get("client_id"))
                : Objects.equals(expected.getDataDomainId(), source.get("data_domain_id")));
        if (existing == null || !existing.found()
                || !Objects.equals(documentId, existing.id())
                || !Objects.equals(snapshotVersion, existing.version())
                || !Objects.equals(documentId, source == null ? null : source.get("id"))
                || !exactScope
                || !Objects.equals(MemoryDocument.DOCUMENT_KIND_CONVERSATION_SUMMARY,
                        source.get("document_kind"))
                || !Objects.equals(MemoryDocument.PUBLICATION_CLASS_MANIFEST,
                        source.get("publication_class"))
                || !Objects.equals(MemoryDocument.STATE_PUBLISHED, source.get("state"))
                || !Objects.equals(expected.getSnapshotToken(), source.get("snapshot_token"))
                || !Objects.equals(snapshotVersion, longValue(source.get("snapshot_version")))
                || !Objects.equals(expected.getLifecycleGeneration(),
                        longValue(source.get("lifecycle_generation")))
                || !Objects.equals(actualFingerprint, source.get("payload_fingerprint"))
                || !Objects.equals(expectedFingerprint, actualFingerprint)) {
            throw new IllegalStateException(
                    "Conflicting memory snapshot does not match the staged publication");
        }
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    public static String deterministicConversationDocumentId(
            ConversationDataScope scope, String conversationId, String snapshotToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, scope.tenantId());
            updateDigest(digest, scope.mode().name());
            updateDigest(digest, scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT
                    ? scope.callerClientId() : scope.dataDomainId());
            updateDigest(digest, scope.ownerJiacn());
            updateDigest(digest, conversationId);
            updateDigest(digest, snapshotToken);
            return "memory-v2-"
                    + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    static String canonicalPayloadFingerprint(Map<?, ?> source) {
        Objects.requireNonNull(source, "source");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : PAYLOAD_FIELDS) {
                updateDigest(digest, field);
                if (!source.containsKey(field)) {
                    digest.update((byte) 0);
                    continue;
                }
                digest.update((byte) 1);
                Object value = source.get(field);
                if (value == null) {
                    throw new IllegalStateException("Memory payload field is null: " + field);
                }
                if (STRING_FIELDS.contains(field)) {
                    if (!(value instanceof String stringValue)) {
                        throw invalidPayloadType(field);
                    }
                    digest.update((byte) 'S');
                    updateDigest(digest, stringValue);
                } else if (LONG_FIELDS.contains(field)) {
                    digest.update((byte) 'L');
                    updateLong(digest, exactLong(value, field));
                } else if ("conversation_count".equals(field)) {
                    long count = exactLong(value, field);
                    if (count < Integer.MIN_VALUE || count > Integer.MAX_VALUE) {
                        throw invalidPayloadType(field);
                    }
                    digest.update((byte) 'I');
                    updateLong(digest, count);
                } else if (STRING_LIST_FIELDS.contains(field)) {
                    if (!(value instanceof List<?> list)) {
                        throw invalidPayloadType(field);
                    }
                    digest.update((byte) 'A');
                    updateLong(digest, list.size());
                    for (Object element : list) {
                        if (!(element instanceof String stringElement)) {
                            throw invalidPayloadType(field);
                        }
                        updateDigest(digest, stringElement);
                    }
                } else if ("embedding".equals(field)) {
                    if (!(value instanceof List<?> list)) {
                        throw invalidPayloadType(field);
                    }
                    digest.update((byte) 'E');
                    updateLong(digest, list.size());
                    for (Object element : list) {
                        if (!(element instanceof Number number)) {
                            throw invalidPayloadType(field);
                        }
                        double vectorValue = number.doubleValue();
                        if (!Double.isFinite(vectorValue)) {
                            throw invalidPayloadType(field);
                        }
                        updateLong(digest, Double.doubleToLongBits(vectorValue));
                    }
                } else {
                    throw new IllegalStateException("Unhandled memory payload field: " + field);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static IllegalStateException invalidPayloadType(String field) {
        return new IllegalStateException("Invalid memory payload field type: " + field);
    }

    private static long exactLong(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw invalidPayloadType(field);
        }
        long longValue = number.longValue();
        if (value instanceof Float || value instanceof Double) {
            double doubleValue = number.doubleValue();
            if (!Double.isFinite(doubleValue) || doubleValue != (double) longValue) {
                throw invalidPayloadType(field);
            }
        }
        return longValue;
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private void requireExactIdentity(String value, String field) {
        if (!StringUtils.hasText(value) || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    @Override
    public void saveAll(List<MemoryDocument> documents) {
        throw new IllegalStateException("Unscoped bulk memory storage is disabled");
    }

    @Override
    public List<MemoryDocument> searchWithConversationBoost(String jiacn, String userMessage,
            String currentConversationId, int topK, double similarityThreshold) {
        throw new IllegalStateException("Unscoped memory search is disabled");
    }

    @Override
    public List<MemoryDocument> searchWithConversationBoost(
            ConversationDataScope scope, String userMessage, String currentConversationId,
            int topK, double similarityThreshold) {
        Objects.requireNonNull(scope, "scope");
        return searchScoped(scope, userMessage, currentConversationId, topK, similarityThreshold);
    }

    private List<MemoryDocument> searchScoped(
            ConversationDataScope scope, String userMessage,
            String currentConversationId, int topK, double similarityThreshold) {
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
                .knn(k -> {
                    k.field("embedding")
                            .queryVector(toFloatList(queryVector))
                            .k(topK)
                            .numCandidates(100)
                            .filter(f -> f.term(t -> t.field("jiacn.keyword").value(scope.ownerJiacn())))
                            .filter(f -> f.term(t -> t.field("tenant_id.keyword").value(scope.tenantId())))
                            .filter(f -> f.term(t -> t.field("scope_mode.keyword").value(scope.mode().name())))
                            .filter(f -> f.term(t -> t.field("state.keyword").value(MemoryDocument.STATE_PUBLISHED)));
                    if (scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT) {
                        k.filter(f -> f.term(t -> t.field("client_id.keyword").value(scope.callerClientId())));
                    } else {
                        k.filter(f -> f.term(t -> t.field("data_domain_id.keyword").value(scope.dataDomainId())));
                    }
                    return k;
                })
            );

            SearchResponse<Map> response = elasticsearchClient.search(searchRequest, Map.class);

            List<Hit<Map>> candidateHits = response.hits().hits().stream()
                    .filter(hit -> hit.source() != null && matchesSearchScope(hit.source(), scope))
                    .toList();
            List<String> manifestIds = candidateHits.stream()
                    .filter(hit -> isManifestDocument(hit.source()))
                    .map(Hit::id)
                    .toList();
            Set<String> visibleManifestIds = manifestIds.isEmpty()
                    ? Set.of()
                    : memoryPublicationDao.findVisibleDocumentIds(scope, manifestIds);
            List<MemoryDocument> results = new ArrayList<>();
            for (Hit<Map> hit : candidateHits) {
                Map<String, Object> source = hit.source();
                if (isDirectDocument(source) || visibleManifestIds.contains(hit.id())) {
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

    private boolean matchesSearchScope(Map<String, Object> source, ConversationDataScope scope) {
        boolean direct = isDirectDocument(source);
        boolean manifest = isManifestDocument(source);
        if (direct == manifest) {
            return false;
        }
        if (!Objects.equals(scope.ownerJiacn(), source.get("jiacn"))
                || !Objects.equals(scope.tenantId(), source.get("tenant_id"))
                || !Objects.equals(scope.mode().name(), source.get("scope_mode"))) {
            return false;
        }
        return scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT
                ? Objects.equals(scope.callerClientId(), source.get("client_id"))
                : Objects.equals(scope.dataDomainId(), source.get("data_domain_id"));
    }

    private boolean isDirectDocument(Map<String, Object> source) {
        return Objects.equals(MemoryDocument.DOCUMENT_KIND_LIBRARY,
                source.get("document_kind"))
                && Objects.equals(MemoryDocument.PUBLICATION_CLASS_DIRECT,
                source.get("publication_class"))
                && Objects.equals(MemoryDocument.STATE_PUBLISHED, source.get("state"));
    }

    private boolean isManifestDocument(Map<String, Object> source) {
        return Objects.equals(MemoryDocument.DOCUMENT_KIND_CONVERSATION_SUMMARY,
                source.get("document_kind"))
                && Objects.equals(MemoryDocument.PUBLICATION_CLASS_MANIFEST,
                source.get("publication_class"))
                && Objects.equals(MemoryDocument.STATE_PUBLISHED, source.get("state"));
    }

    @Override
    public List<MemoryDocument> search(String jiacn, float[] queryVector, int topK) {
        throw new IllegalStateException("Unscoped memory search is disabled");
    }

    @Override
    public List<String> findConversationSummariesByJiacn(String jiacn, long sinceTime) {
        throw new IllegalStateException("Owner-only summary aggregation is disabled");
    }

    @Override
    public List<String> findDailySummariesByJiacn(String jiacn, long sinceTime) {
        throw new IllegalStateException("Owner-only summary aggregation is disabled");
    }

    @Override
    public List<String> findWeeklySummariesByJiacn(String jiacn, long sinceTime) {
        throw new IllegalStateException("Owner-only summary aggregation is disabled");
    }

    @Override
    public void deleteExpiredDocuments(long beforeTime) {
        throw new IllegalStateException("Unscoped memory deletion is disabled");
    }

    @Override
    public void deleteOldDocuments(String summaryType, int keepCount) {
        throw new IllegalStateException("Unscoped memory deletion is disabled");
    }

    @Override
    public long countByJiacn(String jiacn) {
        throw new IllegalStateException("Owner-only memory count is disabled");
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
            return new float[0];
        }
        try {
            // 使用 String 调用 embed 方法
            float[] embedding = embeddingModel.embed(text);
            if (embedding.length > 0) {
                return embedding;
            }
        } catch (Exception e) {
            log.warn("Failed to generate embedding using EmbeddingModel, falling back to hash-based embedding", e);
        }
        // 降级方案：使用 hash 作为种子生成伪向量
        return generateFallbackEmbedding(text);
    }

    /**
     * 降级方案：使用 hash 作为种子生成伪向量
     */
    private float[] generateFallbackEmbedding(String text) {
        int dimension = embeddingDimension;
        float[] embedding = new float[dimension];
        int seed = text.hashCode();
        Random random = new Random(seed);
        for (int i = 0; i < dimension; i++) {
            embedding[i] = random.nextFloat() * 2 - 1;  // 生成 [-1, 1] 范围内的值
        }
        // 归一化
        float norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                embedding[i] /= norm;
            }
        }
        return embedding;
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
        if (doc.getTenantId() != null) map.put("tenant_id", doc.getTenantId());
        if (doc.getDataDomainId() != null) map.put("data_domain_id", doc.getDataDomainId());
        if (doc.getClientId() != null) map.put("client_id", doc.getClientId());
        if (doc.getScopeMode() != null) map.put("scope_mode", doc.getScopeMode());
        if (doc.getConversationId() != null) map.put("conversation_id", doc.getConversationId());
        if (doc.getDocumentKind() != null) map.put("document_kind", doc.getDocumentKind());
        if (doc.getPublicationClass() != null) map.put("publication_class", doc.getPublicationClass());
        if (doc.getState() != null) map.put("state", doc.getState());
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
        if (doc.getSnapshotVersion() != null) map.put("snapshot_version", doc.getSnapshotVersion());
        if (doc.getSnapshotToken() != null) map.put("snapshot_token", doc.getSnapshotToken());
        if (doc.getLifecycleGeneration() != null) map.put("lifecycle_generation", doc.getLifecycleGeneration());
        if (doc.getEmbedding() != null) map.put("embedding", toDoubleList(doc.getEmbedding()));
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryDocument convertFromMap(Map<String, Object> map) {
        MemoryDocument doc = new MemoryDocument();
        if (map.containsKey("id")) doc.setId(String.valueOf(map.get("id")));
        if (map.containsKey("jiacn")) doc.setJiacn(String.valueOf(map.get("jiacn")));
        if (map.containsKey("tenant_id")) doc.setTenantId(String.valueOf(map.get("tenant_id")));
        if (map.containsKey("data_domain_id")) doc.setDataDomainId(String.valueOf(map.get("data_domain_id")));
        if (map.containsKey("client_id")) doc.setClientId(String.valueOf(map.get("client_id")));
        if (map.containsKey("scope_mode")) doc.setScopeMode(String.valueOf(map.get("scope_mode")));
        if (map.containsKey("conversation_id")) doc.setConversationId(String.valueOf(map.get("conversation_id")));
        if (map.containsKey("document_kind")) doc.setDocumentKind(String.valueOf(map.get("document_kind")));
        if (map.containsKey("publication_class")) doc.setPublicationClass(String.valueOf(map.get("publication_class")));
        if (map.containsKey("state")) doc.setState(String.valueOf(map.get("state")));
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
        if (map.containsKey("snapshot_version") && map.get("snapshot_version") instanceof Number)
            doc.setSnapshotVersion(((Number) map.get("snapshot_version")).longValue());
        if (map.containsKey("snapshot_token")) doc.setSnapshotToken(String.valueOf(map.get("snapshot_token")));
        if (map.containsKey("lifecycle_generation") && map.get("lifecycle_generation") instanceof Number)
            doc.setLifecycleGeneration(((Number) map.get("lifecycle_generation")).longValue());
        if (map.containsKey("payload_fingerprint"))
            doc.setPayloadFingerprint(String.valueOf(map.get("payload_fingerprint")));
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
