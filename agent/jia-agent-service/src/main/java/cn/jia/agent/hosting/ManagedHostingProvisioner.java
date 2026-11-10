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
    /** Transaction-external live control/template probe; replay must be resolved before invoking it. */
    default boolean probeCapabilities(String tenantId, String clientId, String ownerJiacn) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Hosting capability I/O inside transaction");
        return availableFor(tenantId, clientId, ownerJiacn);
    }
    /** Public installed inventory only, transaction-external; null is unknown, never zero fees. */
    SkillInventory inventory(String tenantId, String clientId, String ownerJiacn, String agentId);
    record InstalledSkill(String skillKey, String source, String revision) { }
    record SkillInventory(String hostId, String inventorySha256, java.util.List<InstalledSkill> skills) {
        public SkillInventory { skills = java.util.List.copyOf(skills); }
        public long installedSkillCount() { return skills.size(); }
    }
    record Candidate(String installationId, String manifestSha256, String enrollmentSecretSha256,
                     long enrollmentExpiresAt, long provisionGeneration, String hostId) { }
    Observation prepareAndObserve(Preparation preparation);
    /** Read-only original relationship lookup. Null is unknown, never proof that cleanup/release is safe. */
    CurrentOperation currentOperation(Preparation initial);
    record CurrentOperation(Preparation preparation, Candidate candidate) { }
    /** Transaction-external, current relationship only. True proves process stop, NOT private cleanup or unbinding. */
    boolean quiesce(Preparation preparation, Candidate candidate, String handoverId);
    /** All original cost/management evidence accepted; does NOT prove private cleanup or release. */
    boolean flushEvidence(Preparation preparation, Candidate candidate, String handoverId);
    /** Durable private cleanup for the original relationship; does not grant rebinding or release business ownership. */
    boolean cleanup(Preparation preparation, Candidate candidate, String handoverId);
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
