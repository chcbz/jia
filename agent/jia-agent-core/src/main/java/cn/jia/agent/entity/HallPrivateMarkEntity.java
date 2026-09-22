package cn.jia.agent.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** Append-only operation/receipt. Does not change immutable execution or case revision. */
@Data
@Accessors(chain = true)
public class HallPrivateMarkEntity {
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String sourceType;
    private String sourceId;
    private Long revision;
    private String operationKey;
    private String requestHash;
    private Boolean archived;
    private String snapshotExecutionId;
    private String snapshotState;
    private Long snapshotUpdatedAt;
    private String viewedExecutionId;
    private String viewedManifestId;
    private Long updatedAt;
}
