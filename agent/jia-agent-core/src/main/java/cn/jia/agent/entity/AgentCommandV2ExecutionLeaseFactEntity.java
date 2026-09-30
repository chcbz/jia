package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("agent_command_v2_execution_lease_fact")
public class AgentCommandV2ExecutionLeaseFactEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String leaseFactId;
    private String runtimeDispatchId;
    private Long inboxId;
    private Long deliveryId;
    private Long factSequence;
    private Long leaseRevision;
    private String factType;
    private String previousLeaseFactId;
    private Long effectiveAt;
    private Long leaseUntil;
    private String causingReceiptId;
    private String runtimeBindingId;
    private Long dispatchBindingGeneration;
    private Long holderBindingGeneration;
    private String runtimeInstanceId;
    private String runtimeCredentialClientId;
    private String dataDomainId;
    private String executionPoolId;
    private String ownerJiacn;
    private String agentId;
    private String producerDeploymentId;
    private String claimantDeploymentId;
    private Long claimantWorkerGeneration;
    private Long receiverRegistrationRevision;
    private Long admissionEpoch;
    private String moduleId;
    private Long migrationEpoch;
    private String tenantControlOperationId;
    private Long tenantControlRevision;
    private Long tenantControlCutoffAt;
    private String moduleControlOperationId;
    private Long moduleModeRevision;
    private Long moduleControlCutoffAt;
    private String deploymentControlOperationId;
    private Long deploymentRegistrationRevision;
    private Long deploymentControlCutoffAt;
    private String reason;
    private Long createdAt;
    private byte[] executionOriginSnapshotSha256;
    private String holderGenerationFactId;
    private byte[] holderGenerationSnapshotSha256;
}
