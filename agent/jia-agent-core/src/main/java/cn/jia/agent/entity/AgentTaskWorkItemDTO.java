package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskWorkItemDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String workItemId;
    private String taskId;
    private String title;
    private String description;
    private String workType;
    private String requiredAbilities;
    private String assigneeAgentId;
    private String status;
    private Integer priority;
    private Boolean requiredItem;
    private String dependencyJson;
    private String leaseToken;
    private Long leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String resultArtifactId;
    private Long submittedAt;
    private Long completedAt;
    private Long version;
}
