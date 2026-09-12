package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskTeamRecommendationMemberDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String name;
    /** PRODUCER or REVIEWER. A reviewer is never also a producer in the same preview. */
    private String role;
    private Integer score;
    private List<String> matchedAbilities;
    private List<String> marginalCoveredAbilities;
    private Integer costUnits;
    private String reason;
}
