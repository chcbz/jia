package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EconomyUsageRevisionEntity {
    private String tenantId;
    private String clientId;
    private String attemptId;
    private String receiptKey;
    private String receiptId;
    private String requestSha256;
    private String rawUsageJson;
    private String normalizedUsageJson;
    private String priceSnapshotJson;
    private String status;
    private Long revision;
    private java.math.BigDecimal costYuan;
    private Long observedAt;
    private Long recordedAt;
}
