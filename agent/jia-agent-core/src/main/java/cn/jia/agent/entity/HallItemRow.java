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
    private String executionId;
    private Long markRevision;
    private Boolean archived;
    private String viewedExecutionId;
    private String viewedManifestId;
    private Boolean reviewReady;
    private String deliveryId;
    private String workItemId;
    private Long deliveryVersion;
    private Long taskVersion;

}
