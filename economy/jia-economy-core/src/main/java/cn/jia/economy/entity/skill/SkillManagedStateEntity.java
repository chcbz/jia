package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Persistent SM-C1 SkillManagedState facts. */
@Data
@Accessors(chain = true)
public class SkillManagedStateEntity {
    private String tenantId;
    private String clientId;
    private String agentId;
    private String skillKey;
    private Long skillRevision;
    private String activeInstallationId;
    private Long activePolicyRevision;
    private Boolean desiredEnabled;
    private Boolean admissionEnabled;
    private String pendingChangeId;
}
