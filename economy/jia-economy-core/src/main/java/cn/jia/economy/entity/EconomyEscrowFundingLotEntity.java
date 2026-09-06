package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyEscrowFundingLotEntity {
    private Long id;
    private String escrowId;
    private Integer fundingSequence;
    private String reserveTransactionId;
    private String payerAccountId;
    private Long amountMicro;
    private Long escrowGrossAfterMicro;
    private Long escrowVersionAfter;
    private String currency;
    private String tenantId;
    private String clientId;
    private Long createdAt;
}
