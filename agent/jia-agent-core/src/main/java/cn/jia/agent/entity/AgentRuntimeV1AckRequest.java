package cn.jia.agent.entity;

/** Runtime v1 external ACK envelope; server derives the internal ACK timestamp and sender id.
 * deliveryVersion is the last confirmed D06 version, null before the first receipt; it grants no authority.
 * Canonical command bytes do not carry a delivery version; clients must not predict the first CAS version.
 * The HTTP result kind/status/deliveryVersion (D06) is the only command commit receipt. */
public record AgentRuntimeV1AckRequest(
        String messageId, String correlationId, String commandId, String taskId, String workItemId,
        String tenantId, String clientId, String canonicalAgentId, String payloadReference,
        String expiresAt, String status, String installationId, String hostId,
        String runtimeInstanceId, long sessionGeneration, Long deliveryVersion) { }
