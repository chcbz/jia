package cn.jia.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/** Insert-only D09 request/result audit row. */
@Data
@Accessors(chain = true)
@TableName("agent_command_operation_audit")
public class AgentCommandOperationAuditEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String operationId;
    private String phase;
    private String operationType;
    private String tenantId;
    private String clientId;
    private String taskId;
    private String targetAgentId;
    private String commandId;
    private String sourceMessageId;
    private String newMessageId;
    private Long deliveryId;
    private Integer sourceAttempt;
    private Integer newAttempt;
    private byte[] wireHash;
    private String requesterId;
    private String approverId;
    private String reason;
    private String ticketReference;
    private Long requestedAt;
    private Long completedAt;
    private String outcome;
    private String errorCode;
    private String createdBy;
    private Long createdAt;
}
