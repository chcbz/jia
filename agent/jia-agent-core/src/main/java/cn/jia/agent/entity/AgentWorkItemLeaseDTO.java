package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentWorkItemLeaseDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String workItemId;
    private String agentId;
    private String status;
    private String leaseToken;
    private Long leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Long version;
    private Long changedAt;
}
