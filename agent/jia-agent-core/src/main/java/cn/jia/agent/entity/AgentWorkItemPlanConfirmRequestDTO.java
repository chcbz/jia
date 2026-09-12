package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Human-confirmed editable plan. Scope and actor never come from this body. */
@Data
public class AgentWorkItemPlanConfirmRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean confirmed;
    private String sourcePlanId;
    private String sourcePlanDigest;
    private String expectedTaskVersion;
    private List<AgentWorkItemPlanItemDTO> items;
}
