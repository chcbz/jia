package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Exact immutable artifact version selected by an agent for a formal bounty delivery. */
@Data
public class AgentTaskFormalDeliveryItemDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String artifactId;
    private Integer artifactVersion;
    private String contentHash;
    private String purpose;
}
