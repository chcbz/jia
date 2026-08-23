package cn.jia.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * C01 stable write command for appending a task event.
 *
 * <p>This is the business contract. The service validates, allocates
 * an event_version, and produces an {@link AgentTaskEventWriteResult}.
 * Callers MUST NOT pre-assemble an {@link AgentTaskEventEntity}; payloads should
 * be created with {@code TaskEventPayload.builder()} and are revalidated by the writer.
 */
@Data
@Accessors(chain = true)
@Schema(name = "AgentTaskEventWriteCommand", description = "任务事件写入命令")
public class AgentTaskEventWriteCommand implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "租户 scope (max 50 chars, byte-exact)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "客户端 scope (max 50 chars, byte-exact)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clientId;

    @Schema(description = "任务ID (max 100 chars, byte-exact)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;

    @Schema(description = "确定性事件标识符 (max 100 chars)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String eventId;

    @Schema(description = "事件类型 SCREAMING_SNAKE_CASE (max 64 chars)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String eventType;

    @Schema(description = "触发者类型: agent/role/system (max 20 chars)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String actorType;

    @Schema(description = "触发者标识 nullable (max 100 chars)")
    private String actorId;

    @Schema(description = "聚合类型: task/member/work_item/request/artifact/thread/message (max 30 chars)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String aggregateType;

    @Schema(description = "聚合实例ID (max 100 chars)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String aggregateId;

    @Schema(description = "受限元数据 JSON object (NOT NULL, max 4096 UTF-8 bytes)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String eventJson;

    @Schema(description = "事件发生时间 epoch millis (>0)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long occurredAt;
}
