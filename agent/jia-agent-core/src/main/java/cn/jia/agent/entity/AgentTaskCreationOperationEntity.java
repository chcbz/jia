package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Immutable owner-scoped receipt metadata for atomic ordinary-task creation. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_creation_operation")
public class AgentTaskCreationOperationEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String operationId;
    private String ownerJiacn;
    private String idempotencyKey;
    private String requestHash;
    private String operationState;
    private String taskId;
    private Long requirementRevision;
    private String inputRefsJson;
    private Long createdAt;
    private Long completedAt;
}
