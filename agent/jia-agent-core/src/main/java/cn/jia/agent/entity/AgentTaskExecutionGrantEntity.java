package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Persistent, owner-scoped authorization fact. It is not an execution or Provider request. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_execution_grant")
public class AgentTaskExecutionGrantEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String grantId;
    private String ownerJiacn;
    private String taskId;
    private Long requirementRevision;
    private Long assignmentRevision;
    private String targetAgentId;
    private String permittedOperationsJson;
    private String permittedToolPolicyRef;
    private String inputScopeJson;
    private Boolean allowOwnTaskDerivedAssets;
    private String costAuthorizationRef;
    private String sourceBusinessActionId;
    private String idempotencyKey;
    private String requestHash;
    private String policyRevision;
    private Long grantVersion;
    private String state;
    private String issuedBy;
    private Long createdAt;
    private Long revokedAt;
    private String revokeIdempotencyKey;
    private String revokeRequestHash;
}
