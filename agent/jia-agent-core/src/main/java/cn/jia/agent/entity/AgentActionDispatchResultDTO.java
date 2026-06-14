package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentActionDispatchResultDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String intentId;
    private String taskId;
    private String targetAgentId;
    private String status;
    private String message;
    private Long dispatchedAt;
}
