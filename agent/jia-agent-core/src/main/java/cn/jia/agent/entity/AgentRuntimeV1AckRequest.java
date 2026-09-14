package cn.jia.agent.entity;

/** Runtime v1 external ACK envelope; server derives the internal ACK timestamp and sender id. */
public record AgentRuntimeV1AckRequest(
        String messageId, String correlationId, String commandId, String taskId, String workItemId,
        String tenantId, String clientId, String canonicalAgentId, String payloadReference,
        String expiresAt, String status) { }
