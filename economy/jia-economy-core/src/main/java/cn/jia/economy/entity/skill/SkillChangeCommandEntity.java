package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Persistent SM-C1 SkillChangeCommand facts. */
@Data
@Accessors(chain = true)
public class SkillChangeCommandEntity {
    private String tenantId;
    private String clientId;
    private String commandId;
    private String changeId;
    private String requestId;
    private String messageId;
    private String dataJson;
    private String state;
    private String preparedSha256;
    private String evidenceJson;
}
