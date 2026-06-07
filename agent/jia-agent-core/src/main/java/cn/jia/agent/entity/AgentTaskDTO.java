package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String title;
    private String description;
    private String status;
    private List<String> requiredAbilities;
    private Integer reward;
    private String assignedAgentId;
    private String assignedAgentName;
    private Long createdAt;
    private Long updatedAt;
    private Long assignedAt;
    private Long startedAt;
    private Long completedAt;
    private String failureReason;
}
