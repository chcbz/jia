package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyTaskCostEntity {
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String taskId;
    private Long attempts;
    private Long pendingUsage;
    private Long pendingPrice;
    private java.math.BigDecimal costYuan;
    private Long updatedAt;
}
