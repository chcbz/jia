package cn.jia.agent.entity;

/** Canonical A06 ACK plus authoritative WebSocket session scope. */
public record AgentCommandAck(
        String tenantId,
        String clientId,
        String registeredAgentId,
        String messageId,
        String correlationId,
        String commandId,
        String taskId,
        String workItemId,
        String ackStatus,
        long ackAt) {
}
