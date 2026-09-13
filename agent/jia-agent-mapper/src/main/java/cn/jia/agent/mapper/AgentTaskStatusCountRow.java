package cn.jia.agent.mapper;

import lombok.Data;

/** Exact-scope grouped result for the task status-count endpoint. */
@Data
public class AgentTaskStatusCountRow {
    private String tenantId;
    private String clientId;
    private String status;
    private Long taskCount;
}
