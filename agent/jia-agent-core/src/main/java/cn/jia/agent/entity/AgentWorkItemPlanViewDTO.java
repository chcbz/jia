package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentWorkItemPlanViewDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String sourcePlanId;
    private String sourcePlanDigest;
    private String confirmedPlanDigest;
    private String expectedTaskVersion;
    private boolean confirmationRequired;
    private boolean confirmed;
    private boolean idempotentReplay;
    private List<AgentWorkItemPlanItemViewDTO> items;
}
