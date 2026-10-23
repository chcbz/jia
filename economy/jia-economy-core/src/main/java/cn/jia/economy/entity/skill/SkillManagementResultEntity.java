package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Persistent SM-C1 SkillManagementResult facts. */
@Data
@Accessors(chain = true)
public class SkillManagementResultEntity {
    private String tenantId;
    private String clientId;
    private String messageId;
    private String resultSha256;
    private String receiptJson;
}
