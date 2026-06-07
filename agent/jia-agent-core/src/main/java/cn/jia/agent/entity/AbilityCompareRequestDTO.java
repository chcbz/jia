package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AbilityCompareRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private List<String> agentNames;
    private String taskContext;
    private List<String> requiredAbilities;
}
