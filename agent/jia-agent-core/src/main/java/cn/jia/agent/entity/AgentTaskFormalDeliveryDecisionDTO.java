package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Owner-only decision for a submitted formal delivery batch. */
@Data
public class AgentTaskFormalDeliveryDecisionDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String deliveryId;
    private Long expectedTaskVersion;
    private Long expectedDeliveryVersion;
    /** Exact R2 values: {@code accepted} or {@code changes_requested}. */
    private String decision;
    /** Required only for {@code changes_requested}; persisted but never copied to task events. */
    private String reviewReason;
}
