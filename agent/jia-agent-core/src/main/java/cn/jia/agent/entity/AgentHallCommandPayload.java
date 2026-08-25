package cn.jia.agent.entity;

/** Typed Hall action payload shared by all non-TASK_INVITE Protocol v1 commands. */
public record AgentHallCommandPayload(
        String actionType,
        String instruction,
        String conversationType,
        String reason,
        String conversationId,
        String triggerEventId,
        String autonomyLevel,
        Boolean requiresApproval,
        AgentHallCommandContext context) implements AgentCommandPayload {
}
