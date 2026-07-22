package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskArtifactDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String artifactId;
    private String taskId;
    private String workItemId;
    private String producerAgentId;
    private String artifactType;
    private String title;
    private String content;
    private String storageUri;
    private String contentHash;
    private Integer artifactVersion;
    private String visibility;
    private String metadataJson;
    private Long createdAt;
}
