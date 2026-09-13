package cn.jia.agent.entity;

import lombok.Data;

/** Mapper-only row for the accepted authoritative artifact surface. */
@Data
public class AgentTaskAcceptedArtifactRow {
    private String tenantId;
    private String clientId;
    private String taskId;
    private String artifactId;
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
