package cn.jia.agent.entity;

/** Runtime v1 external ACK envelope; server derives the internal ACK timestamp and sender id.
 * deliveryVersion is the last confirmed D06 version, null before the first receipt; it grants no authority.
 * Canonical command bytes do not carry a delivery version; clients must not predict the first CAS version.
 * canonicalAgentId is the sealed-manifest subject after exact raw tenantId/clientId/targetAgentId matching.
 * Installation/host/boot/generation are the current authenticated transport, never SKILL_INSTALL's product installation.
 * payloadReference is nullable: canonical wire without this field projects null, never an invented source reference.
 * expiresAt is the ISO projection of safe raw epoch milliseconds; it is informational, not the authoritative D06 expiry.
 * workItemId remains nullable. None of these projections removes persistent command/source/context/lease validation.
 * Keep raw dispatch bytes/fingerprint immutable across transport rotation and ACK projection.
 * The HTTP result kind/status/deliveryVersion (D06) is the only command commit receipt. */
public record AgentRuntimeV1AckRequest(
        String messageId, String correlationId, String commandId, String taskId, String workItemId,
        String tenantId, String clientId, String canonicalAgentId, String payloadReference,
        String expiresAt, String status, String installationId, String hostId,
        String runtimeInstanceId, long sessionGeneration, Long deliveryVersion) { }
