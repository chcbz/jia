package cn.jia.agent.entity;

/** Typed Hall action payload shared by the canonical Hall Protocol v1 command allowlist. */
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
