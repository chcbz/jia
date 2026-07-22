package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_artifact")
public class AgentTaskArtifactEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String artifactId;
    private String taskId;
    private String workItemId;
    private String producerAgentId;
    private String artifactType;
    private String title;
    private String content;
    private String storageUri;
    private String contentHash;
    private Integer artifactVersion;
    private String visibility;
    private String metadataJson;
    private Long createdAt;
}
