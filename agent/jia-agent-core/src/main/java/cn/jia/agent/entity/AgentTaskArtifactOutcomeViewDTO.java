package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Bounded metadata-only authoritative artifact projection for Context Pack assembly. */
@Data
public class AgentTaskArtifactOutcomeViewDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String artifactId;
    private String taskId;
    private String workItemId;
    private String producerAgentId;
    private String artifactType;
    private String title;
    private String contentHash;
    private Integer artifactVersion;
    private String visibility;
    private Long createdAt;
    private String outcomeState;
    private Long outcomeVersion;
    private String decisionId;
    private String decidedByAgentId;
    private Long decidedAt;
}
