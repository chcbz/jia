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
@TableName("agent_outbox_event")
public class AgentOutboxEventEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String messageId;
    private String commandId;
    private Long deliveryId;
    private String aggregateType;
    private String aggregateId;
    private String destination;
    private String routingKey;
    private byte[] wirePayload;
    private byte[] wirePayloadHash;
    private String status;
    private Integer attemptCount;
    private Long nextRetryAt;
    private String leaseOwner;
    private Long leaseUntil;
    private Integer activeAttempt;
    private Long expiresAt;
    private String publisherConfirmStatus;
    private String mandatoryReturnStatus;
    private String lastError;
    private Long version;
}
