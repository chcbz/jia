package cn.jia.agent.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** Minimum scoped read projection; never carries prompts, tokens, file locations or funding claims. */
@Data
@Accessors(chain = true)
public class HallItemRow {
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String sourceType;
    private String sourceId;
    private String title;
    private String state;
    private String targetAgentId;
    private Long updatedAt;
}
