package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyAccountEntity {
    private Long id;
    private String accountId;
    private String ownerType;
    private String ownerId;
    private String purpose;
    private String currency;
    private Long balanceMicro;
    private Integer allowNegative;
    private String status;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;
}
