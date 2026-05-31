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
    private String assignedAgentId;
    private String assignedAgentName;
    private Long updatedAt;
}
