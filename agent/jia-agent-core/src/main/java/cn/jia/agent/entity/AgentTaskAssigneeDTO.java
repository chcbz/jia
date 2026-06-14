package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskAssigneeDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String agentName;
    private String status;
}
