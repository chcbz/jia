package cn.jia.agent.entity;

import lombok.Data;

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
    /** Lowercase SHA-256 hex of inline content or externally stored bytes. */
    private String contentHash;
    /** Must equal expectedPreviousVersion + 1. */
    private Integer artifactVersion;
    /** Zero creates a new logical artifact; otherwise the exact latest version expected by the caller. */
    private Integer expectedPreviousVersion;
    private String visibility;
    private Map<String, Object> metadata;
}
