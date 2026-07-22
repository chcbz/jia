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
@TableName("agent_task_member")
public class AgentTaskMemberEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String agentId;
    private String memberRole;
    private String memberStatus;
    private String assignmentSource;
    private Long joinedAt;
    private Long acceptedAt;
    private Long startedAt;
    private Long completedAt;
    private Long lastHeartbeatAt;
    private String failureReason;
    private Long version;
}
