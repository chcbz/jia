package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Immutable file/version authorization snapshot for one private execution. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_personal_workspace_execution_input")
public class PersonalWorkspaceExecutionInputEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String inputRef;
    private String executionId;
    private String ownerJiacn;
    private String fileId;
    private Integer fileVersion;
    private String originalFilename;
    private String contentMimeType;
    private Long byteLength;
    private String contentHash;
    private String storageUri;
    private String grantState;
    private Long createdAt;
    private Long revokedAt;
}
