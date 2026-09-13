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
@TableName("agent_task_artifact_outcome")
public class AgentTaskArtifactOutcomeEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String artifactId;
    private Integer artifactVersion;
    private String outcomeState;
    private String supersededByArtifactId;
    private Integer supersededByArtifactVersion;
    private String decisionId;
    private String decisionDigest;
    private String decidedByAgentId;
    private Long decidedAt;
    private Long version;
}
