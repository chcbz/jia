package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Non-secret permanent reassignment receipt view. The lease token is intentionally absent. */
@Data
public class AgentWorkItemReassignmentResultDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String reassignmentId;
    private String taskId;
    private String workItemId;
    private String previousAgentId;
    private String targetAgentId;
    private String sourceCommandId;
    private String commandId;
    private String messageId;
    private String status;
    private Long taskVersion;
    private Long workItemVersion;
    private Long leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Long createdAt;
    private boolean idempotentReplay;
}
