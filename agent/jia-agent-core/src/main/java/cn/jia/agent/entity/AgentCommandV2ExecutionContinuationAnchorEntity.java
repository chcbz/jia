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
@TableName("agent_command_v2_execution_continuation_anchor")
public class AgentCommandV2ExecutionContinuationAnchorEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String runtimeDispatchId;
    private String startedReceiptId;
    private Long holderBindingGeneration;
    private String executionGrantId;
    private Long createdAt;
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
    private byte[] executionOriginSnapshotSha256;
    private String holderGenerationFactId;
    private byte[] holderGenerationSnapshotSha256;
}
