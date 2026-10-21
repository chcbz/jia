package cn.jia.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

/** Immutable successful switch operation; latest revision is the current policy. */
@Data @Accessors(chain = true) @TableName("agent_external_service_policy")
public class AgentExternalServicePolicyEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String tenantId;
    private String clientId;
    private String providerOwnerJiacn;
    private String agentId;
    private Long bindingId;
    private Long revision;
    private Long expectedRevision;
    private Boolean enabled;
    private String idempotencyKey;
    private String requestHash;
    private Long createdAt;
}
