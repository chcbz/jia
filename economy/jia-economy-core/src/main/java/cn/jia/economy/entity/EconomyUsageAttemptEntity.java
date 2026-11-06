package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyUsageAttemptEntity {
    private String tenantId;
    private String clientId;
    private String attemptId;
    private String ownerJiacn;
    private String taskId;
    private String source;
    private String sourceAttemptId;
    private String status;
    private Long revision;
    private java.math.BigDecimal costYuan;
    private Long createdAt;
    private Long updatedAt;
}
