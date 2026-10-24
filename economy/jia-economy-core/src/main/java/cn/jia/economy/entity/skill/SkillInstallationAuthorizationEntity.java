package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Persistent SM-C1 SkillInstallationAuthorization facts. */
@Data
@Accessors(chain = true)
public class SkillInstallationAuthorizationEntity {
    private String tenantId;
    private String clientId;
    private String installationId;
    private String changeId;
    private String entitlementId;
    private String packageJson;
    private String detailsJson;
    private String permissionsJson;
    private String permissionsSha256;
    private String runtimeInstallationId;
    private String runtimeHostId;
    /** Offline, verified provenance projection; never a fabricated SM-C1 command/result. */
    private String adoptionEvidenceJson;
}
