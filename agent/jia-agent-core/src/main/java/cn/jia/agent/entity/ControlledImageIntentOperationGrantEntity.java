package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import java.io.Serial;

/** Independent owner-issued operation authority for exactly one schema-3 execution intent. */
@Data @EqualsAndHashCode(callSuper=true) @Accessors(chain=true)
@TableName("agent_controlled_image_intent_operation_grant")
public class ControlledImageIntentOperationGrantEntity extends BaseEntity {
    @Serial private static final long serialVersionUID=1L;
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String operationGrantId; private String ownerJiacn; private String taskId;
    private String targetAgentId; private String conversationId; private Long conversationGeneration;
    private String interactionIdempotencyKey; private String requestId; private String stepId;
    private String executionIntentId; private String baselineGrantId; private Long baselineGrantVersion;
    private Long taskVersion; private Long assignmentRevision; private Long requirementRevision;
    private String requirementSha256; private String operation; private String instructionSha256;
    private String sourceSnapshotSha256; private String sourceSnapshotJson; private String ownerPayloadSha256;
    private String consentId; private String issueIdempotencyKey; private String issueRequestDigest;
    private String state; private Long version; private String reservedExecutionId; private String reservedRunId;
    private String consumedLeaseId; private String revokeIdempotencyKey; private String revokeRequestDigest;
    private Long revokedAt; private Long createdAt;
}
