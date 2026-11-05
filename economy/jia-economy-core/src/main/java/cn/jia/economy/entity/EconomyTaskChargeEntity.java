package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** One task charge, with a fixed quote. RESERVING never commits; LEGACY_FREE is migration-only. */
@Data
@Accessors(chain = true)
public class EconomyTaskChargeEntity {
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String taskId;
    private String quoteId;
    private String payerUserId;
    private Long amountWen;
    private String state;
    private String reserveTransactionId;
    private Long escrowVersion;
    private String terminalTransactionId;
    private Long createdAt;
    private Long updatedAt;
}
