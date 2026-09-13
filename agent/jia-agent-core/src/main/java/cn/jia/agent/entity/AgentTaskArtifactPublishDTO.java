package cn.jia.agent.entity;

import lombok.Data;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class AgentTaskArtifactPublishDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String artifactId;
    private String workItemId;
    private String producerAgentId;
    private String artifactType;
    private String title;
    private String content;
    private String storageUri;
    /**
     * Base64 in JSON through Jackson's byte[] binding. Used only for managed file storage and never
     * persisted in the artifact table. Exactly one of content, contentBytes, or storageUri is used.
     */
    @ToString.Exclude
    private byte[] contentBytes;
    /** Exact allowlisted MIME type for managed content; parameters and aliases are rejected. */
    private String contentMimeType;
    /** Lowercase SHA-256 hex expectation; managed/inline hashes are calculated server-side. */
    private String contentHash;
    /** Optional exact byte-length expectation; inline text is measured as UTF-8. */
    private Long contentByteLength;
    /** Must equal expectedPreviousVersion + 1. */
    private Integer artifactVersion;
    /** Zero creates a new logical artifact; otherwise the exact latest version expected by the caller. */
    private Integer expectedPreviousVersion;
    private String visibility;
    private Map<String, Object> metadata;

    public boolean hasContentBytes() {
        return contentBytes != null;
    }

    public byte[] getContentBytes() {
        return contentBytes == null ? null : contentBytes.clone();
    }

    public void setContentBytes(byte[] contentBytes) {
        this.contentBytes = contentBytes == null ? null : contentBytes.clone();
    }
}
