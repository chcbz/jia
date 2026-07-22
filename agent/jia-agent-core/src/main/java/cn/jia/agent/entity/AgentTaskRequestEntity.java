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
@TableName("agent_task_request")
public class AgentTaskRequestEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String taskId;
    private String workItemId;
    private String requesterAgentId;
    private String targetType;
    private String targetId;
    private String requestType;
    private String status;
    private Integer priority;
    private String title;
    private String description;
    private String responseJson;
    private Long dueAt;
    private Long acknowledgedAt;
    private Long resolvedAt;
    private Long version;
}
