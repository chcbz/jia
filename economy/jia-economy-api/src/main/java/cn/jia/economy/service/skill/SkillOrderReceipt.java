package cn.jia.economy.service.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Trade snapshot/command DTO; contains no persistence annotations or installation authority. */
@Data
@Accessors(chain = true)
public class SkillOrderReceipt {
    private Long id;
    private String orderId;
    private String actorType;
    private String actorId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private Long orderVersion;
    private String orderStatus;
    private Long priceWen;
    private Long permissionGrantVersion;
    private byte[] approvedPermissionsSha256;
    private String escrowId;
    private String tenantId;
    private String clientId;
    private Long createTime;
}
