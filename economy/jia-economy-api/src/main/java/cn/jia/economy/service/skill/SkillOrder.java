package cn.jia.economy.service.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Trade snapshot/command DTO; contains no persistence annotations or installation authority. */
@Data
@Accessors(chain = true)
public class SkillOrder {
    private Long id;
    private String orderId;
    private String quoteId;
    private String productVersionId;
    private String targetAgentId;
    private String buyerType;
    private String buyerId;
    private String sellerType;
    private String sellerId;
    private Long priceMicro;
    private Long expectedAgentVersion;
    private Long permissionGrantVersion;
    private String approvedPermissionsManifest;
    private byte[] approvedPermissionsSha256;
    private String escrowId;
    private String reserveTransactionId;
    private String captureTransactionId;
    private String refundTransactionId;
    private String status;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long heldAt;
    private Long installingAt;
    private Long activeAt;
    private Long refundedAt;
    private Long updateTime;
}
