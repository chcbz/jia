package cn.jia.agent.hosting;

/**
 * Trusted internal managed-host adapter, never an HTTP callback. Implementations must reconcile the
 * same intent idempotently, use canonical Agent + intent names for profiles/paths, and preserve all
 * pre-existing data. A file write or generic Agent-online flag is NOT service readiness evidence.
 * Production adapter uses only the permission-restricted channel in the existing runner.
 */
public interface ManagedHostingProvisioner {
    /** Pure configuration/capability check; must perform no external I/O. */
    boolean available();
    /** Pure exact-scope capability check before a NEW reserve; replay never needs runtime capability. */
    default boolean availableFor(String tenantId, String clientId, String ownerJiacn) { return available(); }
    Observation prepareAndObserve(Preparation preparation);
    record Preparation(String tenantId, String clientId, String ownerJiacn, String agentId,
                       String intentId, String leaseId, String bindingId, long reservedAt,
                       String operationId, long requestedAt, Long validUntil) {
        public Preparation(String tenantId, String clientId, String ownerJiacn, String agentId,
                           String intentId, String leaseId, String bindingId, long reservedAt) {
            this(tenantId, clientId, ownerJiacn, agentId, intentId, leaseId, bindingId, reservedAt,
                    intentId, reservedAt, null);
        }
    }
    record Observation(Preparation preparation, Outcome outcome, String evidenceRef, Long serviceReadyAt) {
        public Observation(Preparation preparation, Outcome outcome, String evidenceRef) {
            this(preparation, outcome, evidenceRef, null);
        }
    }
    enum Outcome { SERVICE_READY, FAILED_NO_EFFECT, UNKNOWN }
}
