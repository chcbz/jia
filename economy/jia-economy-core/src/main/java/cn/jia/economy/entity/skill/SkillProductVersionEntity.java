package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkillProductVersionEntity {
    private Long id;
    private String productVersionId;
    private String productId;
    private Long versionSequence;
    private String skillKey;
    private String skillVersion;
    private Long priceMicro;
    private byte[] packageSha256;
    private Long packageSize;
    private String approvedPermissionsManifest;
    private byte[] approvedPermissionsSha256;
    private String deploymentRestriction;
    private String reviewStatus;
    private String tenantId;
    private String clientId;
    private Long createTime;
}
