package cn.jia.agent.entity;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingDTO;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String tenantId;
    private String clientId;
    private String title;
    private String description;
    private String status;
    private List<String> requiredAbilities;
    private Integer reward;
    private String assignedAgentId;
    private String assignedAgentName;
    private List<String> assignedAgentIds;
    private List<AgentTaskAssigneeDTO> assignees;
    private List<AgentActionDispatchResultDTO> actionDispatchResults;
    private Long createdAt;
    private Long updatedAt;
    private Long assignedAt;
    private Long startedAt;
    private Long completedAt;
    private String failureReason;
    private String taskVersion;
    private List<AgentSkillRequirementDTO> requiredSkillRequirements;
    private AgentTaskFundingDTO funding;
}
