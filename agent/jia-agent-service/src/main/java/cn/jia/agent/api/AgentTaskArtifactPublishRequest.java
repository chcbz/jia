package cn.jia.agent.api;

import lombok.Data;
import lombok.ToString;

import java.util.Map;

/**
 * Public managed-content publish request. Scope, producer, storage location, digest, and byte length
 * are intentionally absent because the authenticated adapter derives or computes them.
 */
@Data
public final class AgentTaskArtifactPublishRequest {
    private String artifactId;
    private String workItemId;
    private String artifactType;
    private String title;
    @ToString.Exclude
    private byte[] contentBytes;
    private String contentMimeType;
    private Object artifactVersion;
    private Object expectedPreviousVersion;
    private String visibility;
    private Map<String, Object> metadata;

}
