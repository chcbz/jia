package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkillOrderReceiptEntity {
    private Long id;
    private String orderId;
    private String actorType;
    private String actorId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private Long orderVersion;
    private String orderStatus;
    private Long priceMicro;
    private Long permissionGrantVersion;
    private byte[] approvedPermissionsSha256;
    private String escrowId;
    private String tenantId;
    private String clientId;
    private Long createTime;
}
