package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyHostingProvisioningIntentEntity {
    private Long id;
    private String intentId;
    private String leaseId;
    private String quoteId;
    private String quotePurpose;
    private String principalType;
    private String principalId;
    private String personaCode;
    private String agentId;
    private Long amountMicro;
    private Long periodSeconds;
    private String status;
    private byte[] reserveIdempotencyKey;
    private byte[] reserveRequestHash;
    private String reserveTransactionId;
    private Long reservedAt;
    private Long escrowVersion;
    private byte[] captureIdempotencyKey;
    private byte[] captureRequestHash;
    private String captureTransactionId;
    private Long capturedAt;
    private byte[] refundIdempotencyKey;
    private byte[] refundRequestHash;
    private String refundTransactionId;
    private Long refundedAt;
    private String outcomeEvidenceRef;
    private Long serviceReadyAt;
    private Long paidFrom;
    private Long paidThrough;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;
}
