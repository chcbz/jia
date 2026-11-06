package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** Frozen whole-task estimate and prices; no update/delete mapper exists. */
@Data
@Accessors(chain = true)
public class EconomyTaskQuoteEntity {
    private String quoteId;
    private String bindingSha256;
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String taskId;
    private String payerUserId;
    private Long amountWen;
    private String snapshotJson;
    private Long createdAt;
}
