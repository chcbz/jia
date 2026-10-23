package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Persistent SM-C1 SkillManagementRequest facts. */
@Data
@Accessors(chain = true)
public class SkillManagementRequestEntity {
    private String tenantId;
    private String clientId;
    private String actorId;
    private String method;
    private String path;
    private String keyHash;
    private String requestSha256;
    private String resourceId;
}
