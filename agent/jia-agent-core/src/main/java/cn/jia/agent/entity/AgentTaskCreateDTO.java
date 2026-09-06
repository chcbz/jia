package cn.jia.agent.entity;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskCreateDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String title;
    private String description;
    private List<String> requiredAbilities;
    private Integer reward;
    private String grossBountyAmountMicro;
    private String settlementPolicy;
    private List<AgentSkillRequirementDTO> requiredSkillRequirements;
}
