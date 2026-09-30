package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Durable owner and operator authority facts; never contains Provider credentials. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_provider_cost_consent")
public class AgentTaskProviderCostConsentEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String consentId;
    private String ownerJiacn;
    private String taskId;
    private String targetAgentId;
    private String idempotencyKey;
    private String requestDigest;
    private String assignmentIdempotencyKey;
    private String assignmentBaseHash;
    private Long taskVersion;
    private Long requirementRevision;
    private String requirementSha256;
    private String inputSnapshotDigest;
    private String inputSnapshotJson;
    private String providerLane;
    private String bindingId;
    private Long bindingEpoch;
    private String modelId;
    private String custody;
    private String operatorIssuer;
    private String operatorPolicyRevision;
    private String pricingMode;
    private Integer maxOutboundRequestAttempts;
    private Long expiresAt;
    private String state;
    private Long version;
    private String boundGrantId;
    private Long boundGrantVersion;
    private Long boundAssignmentRevision;
    private String reservedExecutionId;
    private String reservedRunId;
    private String consumedLeaseId;
    private Long consumedAt;
    private String revokeIdempotencyKey;
    private String revokeRequestDigest;
    private Long revokedAt;
    private Long createdAt;
}
