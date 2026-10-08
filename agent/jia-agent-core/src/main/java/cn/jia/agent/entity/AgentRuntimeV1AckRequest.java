package cn.jia.agent.entity;

/** Runtime v1 external ACK envelope; server derives the internal ACK timestamp and sender id.
 * deliveryVersion is the expected committed version, matched against the D06 result; it grants no authority.
 * The HTTP result kind/status/deliveryVersion (D06) is the only command commit receipt. */
public record AgentRuntimeV1AckRequest(
        String messageId, String correlationId, String commandId, String taskId, String workItemId,
        String tenantId, String clientId, String canonicalAgentId, String payloadReference,
        String expiresAt, String status, String installationId, String hostId,
        String runtimeInstanceId, long sessionGeneration, long deliveryVersion) { }
