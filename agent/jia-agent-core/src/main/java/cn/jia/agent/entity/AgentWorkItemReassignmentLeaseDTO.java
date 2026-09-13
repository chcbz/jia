package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Target-only lease view returned after receipt, command, identity and current-fence validation. */
@Data
public class AgentWorkItemReassignmentLeaseDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String reassignmentId;
    private String commandId;
    private String taskId;
    private String workItemId;
    private String agentId;
    private String status;
    private String leaseToken;
    private Long leaseUntil;
    private Long workItemVersion;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Long changedAt;
}
