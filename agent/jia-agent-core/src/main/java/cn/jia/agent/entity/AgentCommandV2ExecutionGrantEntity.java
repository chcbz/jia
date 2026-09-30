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
@TableName("agent_command_v2_execution_grant")
public class AgentCommandV2ExecutionGrantEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String executionGrantId;
    private String grantKind;
    private String runtimeDispatchId;
    private String startedReceiptId;
    @ToString.Exclude private byte[] startedReceiptSha256;
    private String runtimeBindingId;
    private String runtimeInstanceId;
    private Long dispatchBindingGeneration;
    private Long holderBindingGeneration;
    private String originalStartLeaseFactId;
    private String currentLeaseFactId;
    private Long leaseRevision;
    private Long leaseUntil;
    private String previousExecutionGrantId;
    private Long issuedAt;
    @ToString.Exclude private byte[] grantWireBytes;
    @ToString.Exclude private byte[] grantWireSha256;
    private Integer grantWireContractVersion;
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
    @ToString.Exclude private byte[] executionOriginSnapshotSha256;
    private String holderGenerationFactId;
    @ToString.Exclude private byte[] holderGenerationSnapshotSha256;
}
