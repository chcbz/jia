package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Immutable scoped idempotency record for one committed artifact outcome decision. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_artifact_outcome_decision")
public class AgentTaskArtifactOutcomeDecisionEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String decisionId;
    private String decisionDigest;
    private String acceptedArtifactId;
    private Integer acceptedArtifactVersion;
    private Long acceptedOutcomeVersion;
    private String decidedByAgentId;
    private Long decidedAt;
}
