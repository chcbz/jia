package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Durable assignment-to-Chat admission intent; never an Agent/runtime command. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_bounty_bootstrap_outbox")
public class AgentTaskBountyBootstrapOutboxEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String bootstrapId;
    private String ownerJiacn;
    private String taskId;
    private String sourceBusinessActionId;
    private String payloadHash;
    private Long requirementRevision;
    private String requirementAnchor;
    private Long assignmentRevision;
    private String targetAgentId;
    private String grantId;
    private Long grantVersion;
    private String permittedOperation;
    private String referenceSummaryJson;
    private String referenceSummarySha256;
    private String status;
    private Integer attemptCount;
    private Long nextRetryAt;
    private String leaseOwner;
    private Long leaseUntil;
    private String admittedConversationId;
    private String admittedRequestId;
    private String lastErrorCode;
    private Long version;
    private Long createdAt;
    private Long reconciledAt;
}
