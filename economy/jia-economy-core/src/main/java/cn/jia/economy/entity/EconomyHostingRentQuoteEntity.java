package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyHostingRentQuoteEntity {
    private Long id;
    private String quoteId;
    private String quotePurpose;
    private String planId;
    private Long planVersion;
    private Long amountMicro;
    private Long periodSeconds;
    private String principalType;
    private String principalId;
    private String personaCode;
    private String agentId;
    private String leaseId;
    private Long expectedLeaseVersion;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private Long expiresAt;
    private String tenantId;
    private String clientId;
    private Long createTime;
}
