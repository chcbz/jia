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
    private String quoteId;
    private String agentId;
    private String escrowId;
    private String status;
    private Long grossMicro;
    private Long actualComputeMicro;
    private Long platformFeeMicro;
    private Long agentPayoutMicro;
    private Long refundedMicro;
    private Long taskVersion;
    private Long fundingVersion;
    private Long escrowVersion;
    private Long settledAt;
    private String transactionIds;
}
