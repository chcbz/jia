package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Private owner-to-agent execution; intentionally not a public bounty task projection. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_personal_workspace_execution")
public class PersonalWorkspaceExecutionEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String executionId;
    private String ownerJiacn;
    private String taskId;
    private String runId;
    private String conversationId;
    private String targetAgentId;
    private String instruction;
    private String outputContentMimeType;
    private String executionState;
    private Long grantRevision;
    private String idempotencyKey;
    private String requestHash;
    private String revokeIdempotencyKey;
    private String revokeRequestHash;
    private Long createdAt;
    private Long revokedAt;
}
