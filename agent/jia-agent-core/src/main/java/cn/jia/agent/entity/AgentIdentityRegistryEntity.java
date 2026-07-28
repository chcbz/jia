package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_identity_registry")
public class AgentIdentityRegistryEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String canonicalAgentId;
    private String canonicalType;
    private String lifecycleStatus;
    private String ownerJiacn;
    private Long bindingId;
    private Long provisionedAt;
    private Long activatedAt;
    private Long suspendedAt;
    private Long retiredAt;
    private String auditReason;
}
