package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskMemberDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String agentId;
    private String memberRole;
    private String memberStatus;
    private String assignmentSource;
    private Long joinedAt;
    private Long acceptedAt;
    private Long startedAt;
    private Long completedAt;
    private Long lastHeartbeatAt;
    private String failureReason;
    private Long version;
}
