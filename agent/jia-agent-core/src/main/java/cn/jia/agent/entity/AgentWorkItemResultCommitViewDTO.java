package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentWorkItemResultCommitViewDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String workItemId;
    private String status;
    private Long workItemVersion;
    private Long submittedAt;
    private AgentTaskArtifactViewDTO artifact;
}
