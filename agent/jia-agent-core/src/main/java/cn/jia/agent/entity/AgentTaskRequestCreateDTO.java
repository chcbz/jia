package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskRequestCreateDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String requestId;
    private String workItemId;
    private String requesterAgentId;
    private String targetType;
    private String targetId;
    private String requestType;
    private Integer priority;
    private String title;
    private String description;
    private Long dueAt;
}
