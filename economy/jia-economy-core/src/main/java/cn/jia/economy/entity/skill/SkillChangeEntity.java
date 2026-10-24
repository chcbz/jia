package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Persistent SM-C1 SkillChange facts. */
@Data
@Accessors(chain = true)
public class SkillChangeEntity {
    private String tenantId;
    private String clientId;
    private String changeId;
    private String agentId;
    private String skillKey;
    private String actorId;
    private String ownerJiacn;
    private Long version;
    private String dtoJson;
    private String permissionsJson;
    private String runtimeInstallationId;
    private String runtimeHostId;
    private String entitlementId;
    private String commandId;
    private Long createdAt;
    private Long updatedAt;
}
