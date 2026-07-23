package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskAggregationDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String previousStatus;
    private String status;
    private String decision;
    private Boolean changed;
    private Long taskVersion;
    private Integer memberCount;
    private Integer workItemCount;
    private Integer requiredWorkItemCount;
    private Integer optionalWorkItemCount;
    private Integer requiredSubmittedCount;
    private Integer requiredCompletedCount;
    private Integer requiredBlockedCount;
    private Integer requiredFailedCount;
    private Long calculatedAt;
}
