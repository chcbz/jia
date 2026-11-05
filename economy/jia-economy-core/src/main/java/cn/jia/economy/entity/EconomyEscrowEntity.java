package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyEscrowEntity {
    private Long id;
    private String escrowId;
    private String businessType;
    private String businessId;
    private String payerAccountId;
    private String escrowAccountId;
    private String currency;
    private Long grossWen;
    private Long capturedWen;
    private Long refundedWen;
    private String status;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;
}
