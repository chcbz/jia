package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskStateDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String aggregateType;
    private String taskId;
    private String agentId;
    private String workItemId;
    private String status;
    private Long version;
    private Long changedAt;
}
