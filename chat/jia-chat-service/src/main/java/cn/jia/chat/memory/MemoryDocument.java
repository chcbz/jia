package cn.jia.chat.memory;

import lombok.Data;

import java.util.List;

/**
 * <p>
 * 记忆文档实体
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
@Data
public class MemoryDocument {
    public static final String DOCUMENT_KIND_LIBRARY = "LIBRARY";
    public static final String DOCUMENT_KIND_CONVERSATION_SUMMARY = "CONVERSATION_SUMMARY";
    public static final String PUBLICATION_CLASS_DIRECT = "DIRECT";
    public static final String PUBLICATION_CLASS_MANIFEST = "MANIFEST";
    public static final String STATE_PUBLISHED = "PUBLISHED";

    private String documentKind;
    private String publicationClass;

    private String state;
    /**
     * 稳定文档ID（写入时分配，检索使用ES命中ID）
     */
    private String id;

    /**
     * 用户标识
     */
    private String jiacn;

    /** Authoritative business area for non-legacy documents. */
    private String tenantId;

    /** Authoritative business data domain for non-legacy documents. */
    private String dataDomainId;

    /** Exact legacy client provenance; required in LEGACY_CLIENT mode. */
    private String clientId;

    /** LEGACY_CLIENT or DOMAIN. */
    private String scopeMode;

    /**
     * 会话ID (会话级摘要时使用)
     */
    private String conversationId;

    /**
     * 记忆内容/摘要
     */
    private String content;

    /**
     * 摘要类型: conversation / daily_summary / weekly_summary / monthly_summary
     */
    private String summaryType;

    /**
     * 话题标签
     */
    private String topic;

    /**
     * 关键点列表
     */
    private List<String> keyPoints;

    /**
     * 分类标签
     */
    private List<String> categories;

    /**
     * 用户偏好
     */
    private List<String> preferences;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 周期开始时间
     */
    private Long periodStart;

    /**
     * 周期结束时间
     */
    private Long periodEnd;

    /**
     * 来源会话数量
     */
    private Integer conversationCount;

    /**
     * 向量嵌入 (用于相似度检索)
     */
    private float[] embedding;

    /** Monotonic source snapshot version for idempotent conversation summaries. */
    private Long snapshotVersion;

    /** SHA-256 over lifecycle generation and the complete ordered source message set. */
    private String snapshotToken;

    /** Conversation lifecycle generation captured by the publication. */
    private Long lifecycleGeneration;

    /**
     * SHA-256 over the canonical Elasticsearch payload. The fingerprint field
     * itself is deliberately excluded from the digest.
     */
    private String payloadFingerprint;

    /**
     * 相似度分数 (检索时使用)
     */
    private transient double score;
}
