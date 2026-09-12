package cn.jia.economy.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkillInstallationEntity {
    private Long id;
    private String installationId;
    private String orderId;
    private String productVersionId;
    private String targetAgentId;
    private Integer schemaVersion;
    private String messageType;
    private String messageId;
    private String requestId;
    private String commandType;
    private String commandId;
    private Integer attempt;
    private Long fencingToken;
    private Long deliveryEpoch;
    private String skillKey;
    private String skillVersion;
    private Long packageSize;
    private byte[] packageSha256;
    private String downloadPath;
    private String status;
    private String failureCode;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long startedAt;
    private Long installedAt;
    private Long failedAt;
    private Long updateTime;
}
