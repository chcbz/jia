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
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_runtime_v1_installation")
public class AgentRuntimeV1InstallationEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String installationId;
    private String canonicalAgentId;
    private String manifestVersion;
    private String manifestSha256;
    @ToString.Exclude
    private byte[] enrollmentSecretHash;
    private Long enrollmentExpiresAt;
    private Long enrollmentConsumedAt;
    @ToString.Exclude
    private byte[] runtimeAuthorizationHash;
    private Long runtimeAuthorizationIssuedAt;
    private String status;
    private Long lastHeartbeatAt;
    private Long version;
}
