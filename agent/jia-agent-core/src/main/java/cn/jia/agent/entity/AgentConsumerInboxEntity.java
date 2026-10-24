package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_consumer_inbox")
public class AgentConsumerInboxEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer protocolVersion;
    private String dataDomainId;
    private String executionPoolId;
    private Long admissionEpoch;
    private String producerDeploymentId;
    private String moduleId;
    private Long migrationEpoch;
    private String claimantDeploymentId;
    private Long claimantWorkerGeneration;
    private String consumerName;
    private String messageId;
    private String eventId;
    private String commandId;
    private Long deliveryId;
    private byte[] wirePayload;
    private byte[] wirePayloadHash;
    private String status;
    private String resultStatus;
    private Integer attemptCount;
    private Long nextRetryAt;
    private String leaseOwner;
    private Long leaseUntil;
    private Integer activeAttempt;
    private Long expiresAt;
    private Long processedAt;
    private String lastError;
    private Long version;
    /** Delivery/inbox versions captured when this external-dispatch claim was issued. */
    private Long claimDeliveryVersion;
    private Long claimInboxVersion;
    /** Immutable lower timestamp bound for the current fenced claim attempt. */
    private Long claimStartedAt;
    /** Latest durable protocol-v2 Agent ACK evidence; all fields are written atomically. */
    private Long ackDeliveryVersion;
    private String ackMessageId;
    private String ackStatus;
    private Long ackAt;
    private String ackAgentId;
    private String ackRuntimeInstallationId;
    private String ackRuntimeInstanceId;
    /** Runtime-v2 dispatch and execution columns are projections; immutable facts remain authoritative. */
    private String runtimeDispatchId;
    private String runtimeBindingId;
    private Long runtimeBindingGeneration;
    private String dispatchRuntimeInstanceId;
    private String dispatchRuntimeCredentialClientId;
    private Long dispatchReceiverRegistrationRevision;
    private String evidenceProvenance;
    private String dispatchGenerationFactId;
    private byte[] dispatchGenerationSnapshotSha256;
    private byte[] executionOriginSnapshotSha256;
    private Long dispatchPreparedAt;
    private Long executionStartedAt;
    private Long executionLeaseUntil;
    private Long executionLeaseRevision;
    private String executionHolderBindingId;
    private Long executionHolderBindingGeneration;
    private String executionHolderRuntimeInstanceId;
    private String executionCurrentLeaseFactId;
    private String ackRuntimeBindingId;
    private Long ackRuntimeBindingGeneration;
    private Long ackCommittedAt;
    private String replayParentMessageId;
    private String replayRequesterId;
    private String replayApproverId;
    private String replayReason;
}
