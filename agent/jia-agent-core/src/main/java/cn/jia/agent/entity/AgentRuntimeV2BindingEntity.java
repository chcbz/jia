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
@TableName("agent_runtime_v2_binding")
public class AgentRuntimeV2BindingEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String runtimeBindingId;
    private String credentialKind;
    private String credentialReference;
    private String runtimeInstallationId;
    private Integer contractVersion;
    private byte[] registrationSubjectSha256;
    private String liveRegistrationSubjectSha256;
    private String liveBusinessSubject;
    private String registrationAuthorityId;
    private Long registrationAuthorityRevision;
    private byte[] processPublicKeySpki;
    private byte[] processPublicKeySha256;
    private Long verifierVersion;
    private String verifierKeyId;
    private byte[] reconnectVerifier;
    private String currentRegistrationReceiptId;
    private byte[] currentRegistrationReceiptSha256;
    private String dataDomainId;
    private String executionPoolId;
    private String ownerJiacn;
    private String agentId;
    @Deprecated private String producerDeploymentId;
    private String claimantDeploymentId;
    private Long claimantWorkerGeneration;
    private Long receiverRegistrationRevision;
    private Long admissionEpoch;
    private String moduleId;
    private Long migrationEpoch;
    private String runtimeInstanceId;
    private Long bindingGeneration;
    private String status;
    private Long connectedAt;
    private Long disconnectedAt;
    private Long revokedAt;
    private String revokeReason;
    private String socketOwnerInstanceId;
    private Long socketOwnerEpoch;
    private Long socketOwnerLeaseUntil;
    private String currentSessionFence;
    private Long version;
}
