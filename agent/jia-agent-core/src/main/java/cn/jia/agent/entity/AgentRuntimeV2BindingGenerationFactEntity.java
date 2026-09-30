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
@TableName("agent_runtime_v2_binding_generation_fact")
public class AgentRuntimeV2BindingGenerationFactEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String bindingGenerationFactId;
    private Long factSequence;
    private String previousFactId;
    private String runtimeBindingId;
    private Long bindingGeneration;
    private String eventType;
    private String revokedRuntimeBindingId;
    private String runtimeInstanceId;
    private byte[] registrationSubjectSha256;
    private String registrationAuthorityId;
    private Long registrationAuthorityRevision;
    private String runtimeCredentialClientId;
    private String dataDomainId;
    private String executionPoolId;
    private String ownerJiacn;
    private String agentId;
    private String claimantDeploymentId;
    private Long claimantWorkerGeneration;
    private Long receiverRegistrationRevision;
    private Long admissionEpoch;
    private String moduleId;
    private Long migrationEpoch;
    private byte[] processPublicKeySha256;
    private Long verifierVersion;
    private String registrationReceiptId;
    private byte[] registrationReceiptSha256;
    private String connectionAdmissionKind;
    private String drainControlOperationId;
    private byte[] generationSnapshotSha256;
    private String sessionFence;
    private String socketOwnerInstanceId;
    private Long socketOwnerEpoch;
    private Long socketOwnerLeaseUntil;
    private Long effectiveAt;
    private String reason;
}
