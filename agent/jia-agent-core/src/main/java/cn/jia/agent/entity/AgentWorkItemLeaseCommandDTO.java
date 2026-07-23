package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Command envelope for B04 work-item lease operations.
 * Fields are validated per operation by the domain service.
 */
@Data
public class AgentWorkItemLeaseCommandDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String leaseToken;
    private Long expectedVersion;
    private Long leaseDurationMillis;
}
