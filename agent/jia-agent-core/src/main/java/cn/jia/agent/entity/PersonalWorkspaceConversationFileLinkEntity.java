package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Owner-scoped selection of one immutable personal-workspace file version for one conversation. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_personal_workspace_conversation_file_link")
public class PersonalWorkspaceConversationFileLinkEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String relationId;
    private String ownerJiacn;
    private String conversationId;
    private String fileId;
    private Integer fileVersion;
    private String linkRole;
    private String linkState;
    private Long relationRevision;
    private Long createdAt;
    private Long detachedAt;
}
