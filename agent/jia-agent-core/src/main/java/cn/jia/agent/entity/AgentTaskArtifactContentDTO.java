package cn.jia.agent.entity;

import java.io.Serial;
import java.io.Serializable;

/** Exact artifact-version bytes returned only after task and visibility ACL checks. */
public final class AgentTaskArtifactContentDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String artifactId;
    private final Integer artifactVersion;
    private final String contentHash;
    private final Long contentByteLength;
    private final String contentMimeType;
    private final byte[] content;

    public AgentTaskArtifactContentDTO(String artifactId, Integer artifactVersion,
            String contentHash, Long contentByteLength, String contentMimeType, byte[] content) {
        this.artifactId = artifactId;
        this.artifactVersion = artifactVersion;
        this.contentHash = contentHash;
        this.contentByteLength = contentByteLength;
        this.contentMimeType = contentMimeType;
        this.content = content == null ? null : content.clone();
    }

    public String getArtifactId() {
        return artifactId;
    }

    public Integer getArtifactVersion() {
        return artifactVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Long getContentByteLength() {
        return contentByteLength;
    }

    public String getContentMimeType() {
        return contentMimeType;
    }

    public byte[] getContent() {
        return content == null ? null : content.clone();
    }
}
