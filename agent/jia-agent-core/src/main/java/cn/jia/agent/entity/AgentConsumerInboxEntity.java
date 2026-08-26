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
@TableName("agent_consumer_inbox")
public class AgentConsumerInboxEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String consumerName;
    private String messageId;
    private String eventId;
    private String commandId;
    private Long deliveryId;
    private byte[] wirePayload;
    private byte[] wirePayloadHash;
    private String status;
    private String resultStatus;
    private Integer attemptCount;
    private Long nextRetryAt;
    private String leaseOwner;
    private Long leaseUntil;
    private Integer activeAttempt;
    private Long expiresAt;
    private Long processedAt;
    private String lastError;
    private Long version;
    private String replayParentMessageId;
    private String replayRequesterId;
    private String replayApproverId;
    private String replayReason;
}
