package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskStateTransitionDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String targetStatus;
    private Long expectedVersion;
    private String failureReason;
}
