package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Read-only, response-allowlisted projection for paged Agent task search. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentTaskSearchRow extends AgentTaskMetaEntity {
    private String planTitle;
    private String planDescription;
    private Integer planReward;
    private Long planCreateTime;
    private Long planUpdateTime;
    private Integer fundingPresent;
    private String fundingMode;
    private String fundingStatus;
    private String escrowId;
    private Long grossBountyAmountMicro;
    private Long remainingMicro;
    private String requiredSkillRequirements;
    private Integer fundingProjectionValid;
}
