package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskTeamRecommendationRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Hard cap for producers plus an independent reviewer, when required. */
    private Integer maxTeamSize;
    /** Non-monetary TEAM_SLOT budget. Each selected Agent costs one unit in E02. */
    private Integer budgetUnits;
    /** Required stale-write guard; must exactly match the authoritative task riskLevel. */
    private Boolean highRisk;
    /** Allows low-risk callers to request the same independent-reviewer constraint. */
    private Boolean independentReviewerRequired;
}
