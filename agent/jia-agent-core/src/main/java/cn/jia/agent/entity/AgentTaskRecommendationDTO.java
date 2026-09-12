package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class AgentTaskRecommendationDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private AgentRuntimeDTO agent;
    private AgentCapabilityDTO capability;
    private Integer score;
    private Integer abilityScore;
    private Integer statusScore;
    private Integer successScore;
    private Integer loadScore;
    private Integer recentScore;
    /** Weighted contribution breakdown: ability, availability, success, load, context, riskPenalty. */
    private Map<String, Integer> scoreParts;
    /** Whether this runtime passes the currently enforceable assignment hard constraints. */
    private Boolean eligible;
    /** Stable existing assignment error codes explaining why this runtime is not eligible. */
    private List<String> exclusionReasons;
    private String reason;
    private List<String> matchedAbilities;
}
