package cn.jia.agent.mapper;

/** Aggregate projection for one byte-exact tenant/client/Agent tuple. */
public class AgentTaskStatsRow {
    private String tenantId;
    private String clientId;
    private String agentId;
    private Long taskCount;
    private Long completedTaskCount;
    private Long failedTaskCount;
    private Long completedDurationCount;
    private Long completedDurationSeconds;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Long getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(Long taskCount) {
        this.taskCount = taskCount;
    }

    public Long getCompletedTaskCount() {
        return completedTaskCount;
    }

    public void setCompletedTaskCount(Long completedTaskCount) {
        this.completedTaskCount = completedTaskCount;
    }

    public Long getFailedTaskCount() {
        return failedTaskCount;
    }

    public void setFailedTaskCount(Long failedTaskCount) {
        this.failedTaskCount = failedTaskCount;
    }

    public Long getCompletedDurationCount() {
        return completedDurationCount;
    }

    public void setCompletedDurationCount(Long completedDurationCount) {
        this.completedDurationCount = completedDurationCount;
    }

    public Long getCompletedDurationSeconds() {
        return completedDurationSeconds;
    }

    public void setCompletedDurationSeconds(Long completedDurationSeconds) {
        this.completedDurationSeconds = completedDurationSeconds;
    }
}
