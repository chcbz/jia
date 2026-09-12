package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Editable work-item draft. Identity and assignment are intentionally absent. */
@Data
public class AgentWorkItemPlanItemDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String itemKey;
    private String title;
    private String description;
    private String workType;
    private List<String> requiredAbilities;
    private Integer priority;
    private Boolean requiredItem;
    private List<String> dependsOn;
    private Integer maxAttempts;
}
