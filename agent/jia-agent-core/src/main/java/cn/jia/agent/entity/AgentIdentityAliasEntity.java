package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_identity_alias")
public class AgentIdentityAliasEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long registryId;
    private String canonicalAgentId;
    private String aliasType;
    private String aliasValue;
    private String aliasStatus;
    private Long validFrom;
    private Long validTo;
    @TableField(exist = false)
    private Integer activeKey;
    private String ownerJiacn;
    private String auditReason;
}
