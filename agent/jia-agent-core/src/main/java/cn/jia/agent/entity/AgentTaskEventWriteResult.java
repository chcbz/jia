package cn.jia.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * Stable result contract after a successful event append.
 */
@Data
@Accessors(chain = true)
@Schema(name = "AgentTaskEventWriteResult", description = "事件写入结果")
public class AgentTaskEventWriteResult implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "分配后的事件版本号 (单调递增)")
    private long eventVersion;

    @Schema(description = "操作前 current_event_version")
    private long previousVersion;

    @Schema(description = "操作后 current_event_version")
    private long currentVersion;

    @Schema(description = "插入后完整事件记录")
    private AgentTaskEventEntity event;
}
