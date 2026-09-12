package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Bounded E04 scheduling result; dependency contents are intentionally not exposed. */
@Data
public class AgentWorkItemDependencyResolutionDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private Integer inspectedWorkItemCount;
    private Integer pendingWorkItemCount;
    private List<String> readyWorkItemIds;
    private Boolean changed;
}
