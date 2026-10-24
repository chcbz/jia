package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Persistent SM-C1 SkillManagementQuote facts. */
@Data
@Accessors(chain = true)
public class SkillManagementQuoteEntity {
    private String tenantId;
    private String clientId;
    private String quoteId;
    private String actorId;
    private String requestJson;
    private String dtoJson;
    private String targetJson;
    private String detailsJson;
}
