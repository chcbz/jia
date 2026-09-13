package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskTeamRecommendationCandidateDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String name;
    private Integer score;
    private List<String> roles;
    private List<String> matchedAbilities;
    private Boolean eligible;
    private List<String> exclusionReasons;
    private Boolean selected;
    /** Null when not selected; otherwise PRODUCER or REVIEWER. */
    private String selectionRole;
    private Integer costUnits;
    private String reason;
}
