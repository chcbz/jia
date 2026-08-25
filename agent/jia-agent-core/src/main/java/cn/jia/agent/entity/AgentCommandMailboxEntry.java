package cn.jia.agent.entity;

/** Business-safe durable mailbox projection. Raw transport and replay fields are excluded. */
public record AgentCommandMailboxEntry(
        String commandId,
        String taskId,
        String workItemId,
        String targetAgentId,
        String commandType,
        String status,
        long expiresAt,
        long createTime,
        long updateTime) {
}
