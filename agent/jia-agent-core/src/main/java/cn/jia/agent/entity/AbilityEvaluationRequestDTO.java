package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class AbilityEvaluationRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentName;
    private String evaluationType;
    private String taskContext;
    private List<String> requiredAbilities;
    private Map<String, Integer> scores;
    private String comment;
}
