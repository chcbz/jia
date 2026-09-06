package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkillEntitlementEntity {
    private Long id;
    private String entitlementId;
    private String orderId;
    private String installationId;
    private String productVersionId;
    private String targetAgentId;
    private String skillKey;
    private String skillVersion;
    private Long permissionGrantVersion;
    private String approvedPermissionsManifest;
    private byte[] approvedPermissionsSha256;
    private String status;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long activatedAt;
    private Long failedAt;
    private Long updateTime;
}
