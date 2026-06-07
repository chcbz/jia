package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AbilityCompareResultDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String bestForTask;
    private List<AbilityEvaluationDTO> ranking;
}
