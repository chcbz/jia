package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Input for the bounded, provider-free E03 decomposition suggestion. */
@Data
public class AgentWorkItemPlanSuggestRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String objective;
    private Integer maxItems;
    private String dependencyMode;
}
