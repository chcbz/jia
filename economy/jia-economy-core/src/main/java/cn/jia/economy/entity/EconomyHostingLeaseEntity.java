package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyHostingLeaseEntity {
    private Long id;
    private String leaseId;
    private String principalType;
    private String principalId;
    private String personaCode;
    private String agentId;
    private String bindingId;
    private String planId;
    private Long planVersion;
    private Long amountMicro;
    private Long periodSeconds;
    private String status;
    private Long paidFrom;
    private Long paidThrough;
    private String latestIntentId;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;
}
