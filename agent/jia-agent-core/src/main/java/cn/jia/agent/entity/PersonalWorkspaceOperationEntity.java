package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Durable idempotency receipt; no user content or storage path is exposed through it. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_personal_workspace_operation")
public class PersonalWorkspaceOperationEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String operationId;
    private String ownerJiacn;
    private String operationType;
    private String idempotencyKey;
    private String requestHash;
    private String state;
    private String fileId;
    private Integer fileVersion;
    private String errorCode;
    private Long createdAt;
    private Long completedAt;
}
