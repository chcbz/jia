package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("agent_task_event")
@Schema(name = "AgentTaskEvent对象", description = "任务事件日志 (§7.5)")
public class AgentTaskEventEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "任务范围单调事件版本号")
    private Long eventVersion;

    @Schema(description = "确定性事件标识符")
    private String eventId;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "触发者类型: agent/role/system")
    private String actorType;

    @Schema(description = "触发者标识 (canonical agentId 或 role key)")
    private String actorId;

    @Schema(description = "聚合类型")
    private String aggregateType;

    @Schema(description = "聚合实例ID")
    private String aggregateId;

    @TableField("event_json")
    @Schema(description = "事件负载JSON")
    private String eventJson;

    @TableField("occurred_at")
    @Schema(description = "事件发生时间 (epoch millis)")
    private Long occurredAt;
}
