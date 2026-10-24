package cn.jia.chat.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** SQL authority for one immutable Elasticsearch memory snapshot. */
@Data
@Accessors(chain = true)
public class MemoryPublicationRecord {
    private Long id;
    private String tenantId;
    private String scopeMode;
    private String scopeKey;
    private String ownerJiacn;
    private String conversationId;
    private Long lifecycleGeneration;
    private String snapshotToken;
    private Long esRevision;
    private String documentId;
    private Integer messageCount;
    private String state;
    /** Immutable generated summary selected by the first successful payload binder. */
    private String summaryContent;
    /** Versioned, big-endian float payload selected with {@link #summaryContent}. */
    private byte[] embeddingBytes;
    /** SHA-256 over the canonical generated summary and embedding bytes. */
    private String payloadDigest;
    private Long publishedAt;
    private Long tombstonedAt;
    /** Immutable authority for the Elasticsearch document timestamp across retries. */
    private Long createTime;
    private Long updateTime;

    public byte[] getEmbeddingBytes() {
        return embeddingBytes == null ? null : embeddingBytes.clone();
    }

    public MemoryPublicationRecord setEmbeddingBytes(byte[] embeddingBytes) {
        this.embeddingBytes = embeddingBytes == null ? null : embeddingBytes.clone();
        return this;
    }
}
