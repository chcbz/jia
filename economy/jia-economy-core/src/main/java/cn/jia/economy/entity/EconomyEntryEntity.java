package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyEntryEntity {
    private Long id;
    private String entryId;
    private String transactionId;
    private String accountId;
    private Integer entrySequence;
    private Long signedAmountMicro;
    private Long balanceAfterMicro;
    private String currency;
    private String status;
    private Long postedAt;
    private String tenantId;
    private String clientId;
    private Long createTime;
}
