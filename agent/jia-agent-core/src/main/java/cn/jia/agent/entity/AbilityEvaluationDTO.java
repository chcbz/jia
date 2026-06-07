package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class AbilityEvaluationDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String agentName;
    private String evaluationType;
    private String taskContext;
    private Integer powerScore;
    private Integer intelligenceScore;
    private Integer leadershipScore;
    private Integer abilityMatchScore;
    private Integer overallScore;
    private Map<String, Object> evaluationContent;
    private String comment;
    private Long evaluationTime;
}
