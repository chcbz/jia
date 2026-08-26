package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Durable D09 broker-redrive reservation and pending-to-terminal recovery row. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_command_redrive_operation")
public class AgentCommandRedriveOperationEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String operationId;
    private Long deliveryId;
    private String taskId;
    private String targetAgentId;
    private String commandId;
    private String sourceEventId;
    private String sourceMessageId;
    private Integer sourceAttempt;
    private byte[] wireHash;
    private String requesterId;
    private String reason;
    private String ticketReference;
    private AgentCommandRedriveOutcomeState outcomeState;
    private AgentCommandRedriveSettlementState settlementState;
    private String errorCode;
    private Long requestedAt;
    private Long completedAt;
    private Long version;
    private Integer dispositionGuard;
    private Integer redriveGuard;

    @JsonIgnore
    public AgentCommandRedriveOperationState state() {
        return new AgentCommandRedriveOperationState(outcomeState, settlementState);
    }
}
