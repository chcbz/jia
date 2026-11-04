package cn.jia.economy.service.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Trade snapshot/command DTO; contains no persistence annotations or installation authority. */
@Data
@Accessors(chain = true)
public class SkillPurchaseQuote {
    private Long id;
    private String quoteId;
    private String actorType;
    private String actorId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private String productVersionId;
    private String targetAgentId;
    private Long expectedAgentVersion;
    private Long expectedPriceMicro;
    private String approvedPermissionsManifest;
    private byte[] approvedPermissionsSha256;
    private String deploymentRestriction;
    private Long expiresAt;
    private String tenantId;
    private String clientId;
    private Long createTime;
}
