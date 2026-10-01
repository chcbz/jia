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
@TableName("agent_command_v2_execution_request")
public class AgentCommandV2ExecutionRequestEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String executionRequestId;
    private String requestKind;
    @ToString.Exclude private byte[] requestFingerprint;
    private String runtimeDispatchId;
    private String startedReceiptId;
    private String runtimeBindingId;
    private String runtimeInstanceId;
    private Long requestedHolderBindingGeneration;
    private String decision;
    private String executionGrantId;
    private String denialCode;
    private Long decidedAt;
    private Integer contractVersion;
    private String dataDomainId;
    private String executionPoolId;
    private String runtimeCredentialClientId;
    private String ownerJiacn;
    private String agentId;
    private String producerDeploymentId;
    private String claimantDeploymentId;
    private Long claimantWorkerGeneration;
    private Long receiverRegistrationRevision;
    private Long admissionEpoch;
    private String moduleId;
    private Long migrationEpoch;
    private String evidenceProvenance;
    @ToString.Exclude private byte[] executionOriginSnapshotSha256;
    private String requestedHolderGenerationFactId;
    @ToString.Exclude private byte[] requestedHolderGenerationSnapshotSha256;
}
