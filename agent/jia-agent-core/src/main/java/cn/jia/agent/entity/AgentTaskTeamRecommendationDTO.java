package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskTeamRecommendationDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskVersion;
    private String requirementSource;
    private List<String> requiredAbilities;
    private List<String> coveredAbilities;
    private List<String> missingAbilities;

    private Integer requestedMaxTeamSize;
    private Integer taskMaxAgents;
    /** Effective cap: min(requestedMaxTeamSize, taskMaxAgents). */
    private Integer maxTeamSize;
    private Integer budgetUnits;
    private Integer totalCostUnits;
    private String costModel;
    private Boolean monetaryCostKnown;

    private String riskLevel;
    private Boolean highRisk;
    private Boolean taskReviewRequired;
    private Boolean independentReviewerRequired;
    private Boolean independentReviewerSatisfied;
    private Boolean constraintsSatisfied;
    private Boolean readyForConfirmation;
    private Boolean previewOnly;
    private Boolean autoDispatchAllowed;
    private List<String> blockingReasons;
    private List<String> autoDispatchReasons;
    private List<String> duplicateCandidateAgentIds;

    private List<AgentTaskTeamRecommendationMemberDTO> members;
    private List<AgentTaskTeamRecommendationCandidateDTO> candidates;
}
