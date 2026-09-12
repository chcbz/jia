package cn.jia.agent.entity.funding;

import lombok.Data;

@Data
public class AgentTaskFundingEntity {
    private Long id;
    private String taskId;
    private String fundingMode;
    private String fundingStatus;
    private String payerPrincipalType;
    private String payerPrincipalId;
    private String settlementPolicy;
    private Long grossBountyAmountMicro;
    private Long remainingMicro;
    private String escrowId;
    private Long escrowVersion;
    private String reserveTransactionId;
    private String requiredSkillRequirements;
    private byte[] cancelIdempotencyKey;
    private byte[] cancelRequestHash;
    private String refundTransactionId;
    private Long cancelRefundedMicro;
    private Long cancelTaskVersion;
    private Long refundedAt;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;

    public byte[] getCancelIdempotencyKey() {
        return cancelIdempotencyKey == null ? null : cancelIdempotencyKey.clone();
    }

    public void setCancelIdempotencyKey(byte[] value) {
        cancelIdempotencyKey = value == null ? null : value.clone();
    }

    public byte[] getCancelRequestHash() {
        return cancelRequestHash == null ? null : cancelRequestHash.clone();
    }

    public void setCancelRequestHash(byte[] value) {
        cancelRequestHash = value == null ? null : value.clone();
    }
}
