package cn.jia.agent.entity;

import lombok.Data;

/** One row from the single-statement B05 member/work-item aggregate snapshot. */
@Data
public class AgentTaskAggregationSnapshotRow {
    private String rowType;
    private String tenantId;
    private String clientId;
    private String taskId;
    private String entityId;
    private String role;
    private String status;
    private Boolean requiredItem;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String resultArtifactId;
    private Long completedAt;
    private Long version;
    private String title;
    private String workType;
    private String leaseToken;
    private Long leaseUntil;
    private String assigneeAgentId;
}
