package cn.jia.agent.entity;

import lombok.Data;

/** Explicit safe-column projection for the legacy numeric task_plan source. */
@Data
public class AgentTaskContextPackTaskSourceRow {
    private String tenantId;
    private String clientId;
    private String taskId;
    private Long planId;
    private String title;
    private String description;
}
