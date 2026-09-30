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
@TableName("agent_runtime_v2_registration_receipt")
public class AgentRuntimeV2RegistrationReceiptEntity extends BaseEntity {
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String registrationReceiptId;
    private String receiptKind;
    private String registrationRequestId;
    private String previousRegistrationReceiptId;
    @ToString.Exclude private byte[] previousRegistrationReceiptSha256;
    private String runtimeBindingId;
    private Long bindingGeneration;
    private String bindingGenerationFactId;
    private String runtimeInstanceId;
    @ToString.Exclude private byte[] processPublicKeySha256;
    private Long verifierVersion;
    @ToString.Exclude private byte[] verifierSha256;
    private String verifierCipherSuite;
    @ToString.Exclude private byte[] serverEphemeralPublicKeySpki;
    @ToString.Exclude private byte[] nonce;
    @ToString.Exclude private byte[] sealedVerifier;
    private String dataDomainId;
    private String executionPoolId;
    private String runtimeCredentialClientId;
    private String ownerJiacn;
    private String agentId;
    private String registrationAuthorityId;
    private Long registrationAuthorityRevision;
    private String claimantDeploymentId;
    private Long claimantWorkerGeneration;
    private Long receiverRegistrationRevision;
    private Long admissionEpoch;
    private String moduleId;
    private Long migrationEpoch;
    private Long connectedAt;
    @ToString.Exclude private byte[] receiptWireBytes;
    @ToString.Exclude private byte[] receiptWireSha256;
    private Integer contractVersion;
}
