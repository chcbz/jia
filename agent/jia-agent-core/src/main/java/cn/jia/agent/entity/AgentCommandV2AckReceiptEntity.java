package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("agent_command_v2_ack_receipt")
public class AgentCommandV2AckReceiptEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String receiptId;
    private String runtimeDispatchId;
    private Long inboxId;
    private Long deliveryId;
    private String ackMessageId;
    private String sourceMessageId;
    private String status;
    private Long ackAt;
    @ToString.Exclude private byte[] requestFingerprint;
    private String runtimeBindingId;
    private Long dispatchBindingGeneration;
    private Long ackHolderBindingGeneration;
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
    private Long postcommitDeliveryVersion;
    private Long postcommitInboxVersion;
    private String leaseFactId;
    private Integer contractVersion;
    @ToString.Exclude private byte[] receiptWireBytes;
    @ToString.Exclude private byte[] receiptWireSha256;
    private Integer receiptWireContractVersion;
    private Long committedAt;
    private String evidenceProvenance;
    @ToString.Exclude private byte[] executionOriginSnapshotSha256;
    private String ackHolderGenerationFactId;
    @ToString.Exclude private byte[] ackHolderGenerationSnapshotSha256;
}
