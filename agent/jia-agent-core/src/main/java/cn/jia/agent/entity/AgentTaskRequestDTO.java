package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String requestId;
    private String taskId;
    private String workItemId;
    private String requesterAgentId;
    private String targetType;
    private String targetId;
    private String requestType;
    private String status;
    private Integer priority;
    private String title;
    private String description;
    private String responseJson;
    private Long dueAt;
    private Long acknowledgedAt;
    private Long resolvedAt;
    private Long version;
}
