package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyHostingRentPlanEntity {
    private Long id;
    private String planId;
    private Long planVersion;
    private Long amountMicro;
    private Long periodSeconds;
    private Long quoteTtlSeconds;
    private String currency;
    private String status;
    private String tenantId;
    private String clientId;
    private Long createTime;
}
