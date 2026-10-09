package cn.jia.agent.service;

/** Byte-exact, single-session transport boundary for protocol-v2 commands. */
public interface AgentCommandV2RuntimeGateway {
    /** Stable across Rabbit redelivery and producer reissue. */
    String DEDUPLICATION_KEY_CONTRACT =
            "tenantId,dataDomainId,executionPoolId,commandId,deliveryId";
    /**
     * SHA-256 of the canonical {@code agent-command-business-v1} projection: schemaVersion=1,
     * commandId, correlationId, causationId, tenantId, clientId, ownerJiacn, taskId,
     * workItemId, targetAgentId, commandType, issuedAt, expiresAt, optional intentId and payload.
     * Transport-only messageId, deliveryId, attempt and producer provenance are excluded.
     */
    String SEMANTIC_FINGERPRINT_CONTRACT = "sha256(agent-command-business-v1)";
    String DEDUPLICATION_CONTRACT =
            "key=" + DEDUPLICATION_KEY_CONTRACT
                    + ";semanticFingerprint=" + SEMANTIC_FINGERPRINT_CONTRACT;

    DispatchResult dispatch(CurrentSession session, byte[] wirePayload, long sendTimeoutMillis);

    record CurrentSession(String sessionId, String tenantId, String dataDomainId,
            String executionPoolId, String clientId, String ownerJiacn,
            String agentId, String runtimeInstanceId) {
    }

    record DispatchResult(Kind kind) {
        public static DispatchResult dispatched() { return new DispatchResult(Kind.DISPATCHED); }
        public static DispatchResult offline() { return new DispatchResult(Kind.OFFLINE); }
        public static DispatchResult sendFailed() { return new DispatchResult(Kind.SEND_FAILED); }
        public static DispatchResult rejected() { return new DispatchResult(Kind.REJECTED); }
    }

    enum Kind { DISPATCHED, OFFLINE, SEND_FAILED, REJECTED }
}
