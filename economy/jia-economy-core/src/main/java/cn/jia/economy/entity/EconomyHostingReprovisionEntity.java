package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** Immutable zero-price acceptance snapshots plus CAS reconciliation state. */
@Data
@Accessors(chain = true)
public class EconomyHostingReprovisionEntity {
    private Long id;
    private String requestId;
    private String leaseId;
    private String intentId;
    private String agentId;
    private String personaCode;
    private String principalId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private Long leaseVersion;
    private Long paidThrough;
    private Long requestedAt;
    private String status;
    private Integer liveSlot;
    private Long version;
    private Long serviceReadyAt;
    private String evidenceRef;
    private String tenantId;
    private String clientId;
}
