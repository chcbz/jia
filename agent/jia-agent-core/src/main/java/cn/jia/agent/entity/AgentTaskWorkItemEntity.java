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
@TableName("agent_task_work_item")
public class AgentTaskWorkItemEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String workItemId;
    private String taskId;
    private String title;
    private String description;
    private String workType;
    private String requiredAbilities;
    private String assigneeAgentId;
    private String status;
    private Integer priority;
    private Boolean requiredItem;
    private String dependencyJson;
    private String leaseToken;
    private Long leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String resultArtifactId;
    private Long submittedAt;
    private Long completedAt;
    private Long version;
}
