package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyTransactionEntity {
    private Long id;
    private String transactionId;
    private String principalType;
    private String principalId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private String businessType;
    private String businessId;
    private String currency;
    private String status;
    private Integer entryCount;
    private Long debitTotalMicro;
    private Long creditTotalMicro;
    private Long postedAt;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;
}
