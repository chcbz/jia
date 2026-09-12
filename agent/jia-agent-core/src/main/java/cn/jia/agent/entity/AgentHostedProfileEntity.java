package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_hosted_profile")
public class AgentHostedProfileEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long bindingId;
    private String ownerJiacn;
    private String canonicalAgentId;
    private String personaCode;
    private String profileKey;
    private String apiKeyId;
    private String lifecycleState;
    private String resumeState;
    private Long generation;
    private Boolean desiredEnabled;
    private String lastError;
}
