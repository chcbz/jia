package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** A runtime-uploaded output remains staged until an explicit manifest commit. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_personal_workspace_execution_output")
public class PersonalWorkspaceExecutionOutputEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String outputId;
    private String executionId;
    private String ownerJiacn;
    private String originalFilename;
    private String contentMimeType;
    private Long byteLength;
    private String contentHash;
    private String storageUri;
    private String outputState;
    private String workspaceFileId;
    private Integer workspaceFileVersion;
    /** Task-only artifact projection. No storage URI from either storage boundary is exposed to browsers. */
    private String artifactId;
    private Integer artifactVersion;
    private String formalDeliveryId;
    /** PENDING until the staged bytes have been atomically mapped to workspace/artifact/formal delivery. */
    private String publicationState;
    private Long publicationRevision;
    private String publicationFailureCode;
    private Long stagedAt;
    private Long committedAt;
}
