package cn.jia.agent.output;

/** Persisted business-source authorization projected without implementation-module types. */
public record OutputSourceAuthorization(
        String tenantId,
        String clientId,
        String sourceType,
        String sourceId,
        String ownerJiacn,
        String producerAgentId,
        boolean writable) {
}
