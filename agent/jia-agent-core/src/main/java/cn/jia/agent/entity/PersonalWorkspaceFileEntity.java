package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Owner-scoped upload metadata. It deliberately does not reuse task artifact storage. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_personal_workspace_file")
public class PersonalWorkspaceFileEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String fileId;
    private String ownerJiacn;
    private String sourceKind;
    private String displayName;
    private String mediaFamily;
    private String state;
    private Long metadataRevision;
    private Integer latestVersion;
    private Long createdAt;
}
