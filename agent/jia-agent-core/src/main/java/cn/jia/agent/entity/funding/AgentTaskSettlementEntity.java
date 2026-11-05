package cn.jia.agent.entity.funding;

import lombok.Data;

@Data
public class AgentTaskSettlementEntity {
    private Long id;
    private String tenantId;
    private String clientId;
    private String principalType;
    private String principalId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private String taskId;
    /** Authenticated user that owns this task; separate from the executing Agent. */
    private String ownerJiacn;
    private String quoteId;
    private String agentId;
    private String escrowId;
    private String status;
    private Long grossWen;
    private Long actualComputeWen;
    private Long platformFeeWen;
    private Long agentPayoutWen;
    private Long refundedWen;
    private Long taskVersion;
    private Long fundingVersion;
    private Long escrowVersion;
    private Long settledAt;
    private String transactionIds;
}
