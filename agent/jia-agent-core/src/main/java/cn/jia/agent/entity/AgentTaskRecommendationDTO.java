package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

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
    private String reason;
    private List<String> matchedAbilities;
}
