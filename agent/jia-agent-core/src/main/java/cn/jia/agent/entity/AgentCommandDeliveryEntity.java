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
@TableName("agent_command_delivery")
public class AgentCommandDeliveryEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String commandId;
    private String taskId;
    private String workItemId;
    private String targetAgentId;
    private String commandType;
    private byte[] commandPayload;
    private byte[] commandPayloadHash;
    private String status;
    private Integer attemptCount;
    private Long nextRetryAt;
    private String leaseOwner;
    private Long leaseUntil;
    private String activeMessageId;
    private Integer activeAttempt;
    private Long expiresAt;
    private String lastError;
    private Long version;
    private String replayParentMessageId;
    private String replayRequesterId;
    private String replayApproverId;
    private String replayReason;
}
