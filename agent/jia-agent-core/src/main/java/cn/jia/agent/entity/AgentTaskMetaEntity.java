package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_meta")
@Schema(name = "AgentTaskMeta对象", description = "聚义厅悬赏任务扩展元数据")
public class AgentTaskMetaEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String rewardStatus;
    private String assignedAgentId;
    private String requiredAbilities;
    private Integer reward;
    private Long assignedAt;
    private Long startedAt;
    private Long completedAt;
    private String failureReason;
}
