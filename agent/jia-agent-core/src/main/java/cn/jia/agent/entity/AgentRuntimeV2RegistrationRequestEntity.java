package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

@Data @Accessors(chain = true) @EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_v2_registration_request")
public class AgentRuntimeV2RegistrationRequestEntity extends BaseEntity {
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String registrationRequestId;
    private String requestKind;
    @ToString.Exclude private byte[] requestWireSha256;
    @ToString.Exclude private byte[] semanticSha256;
    @ToString.Exclude private byte[] registrationSubjectSha256;
    @ToString.Exclude private byte[] registrationAuthoritySha256;
    private String runtimeBindingId;
    private Long bindingGeneration;
    private String runtimeInstanceId;
    @ToString.Exclude private byte[] processPublicKeySha256;
    private String registrationAuthorityId;
    private Long registrationAuthorityRevision;
    private String dataDomainId;
    private String executionPoolId;
    private String runtimeCredentialClientId;
    private String ownerJiacn;
    private String agentId;
    private String claimantDeploymentId;
    private Long claimantWorkerGeneration;
    private Long receiverRegistrationRevision;
    private Long admissionEpoch;
    private String moduleId;
    private Long migrationEpoch;
    private String decision;
    private String registrationReceiptId;
    @ToString.Exclude private byte[] registrationReceiptSha256;
    private String denialCode;
    private Long decidedAt;
    private Integer contractVersion;
}
