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
@TableName("agent_runtime_v2_registration_authority")
public class AgentRuntimeV2RegistrationAuthorityEntity extends BaseEntity {
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String registrationAuthorityId;
    private String credentialKind;
    private String credentialReference;
    @ToString.Exclude private byte[] credentialDigest;
    private String credentialKeyId;
    private String ownerJiacn;
    private String agentId;
    private String dataDomainId;
    private String executionPoolId;
    private String moduleId;
    private String claimantDeploymentId;
    private Long authorityRevision;
    private String status;
    private String activeCredentialReference;
    private String activeBusinessSubject;
    private Long revokedAt;
    private String revokeReason;
    private Long version;
}
