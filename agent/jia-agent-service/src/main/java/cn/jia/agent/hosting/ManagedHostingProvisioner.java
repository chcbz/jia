package cn.jia.agent.hosting;

/**
 * Trusted internal managed-host adapter, never an HTTP callback. Implementations must reconcile the
 * same intent idempotently, use canonical Agent + intent names for profiles/paths, and preserve all
 * pre-existing data. A file write or generic Agent-online flag is NOT service readiness evidence.
 * No production adapter is supplied until intent-bound managed readiness is established.
 */
public interface ManagedHostingProvisioner {
    /** Pure configuration/capability check; must perform no external I/O. */
    boolean available();
    Observation prepareAndObserve(Preparation preparation);
    record Preparation(String tenantId, String clientId, String ownerJiacn, String agentId,
                       String intentId, String leaseId, String bindingId, long reservedAt) { }
    record Observation(Preparation preparation, Outcome outcome, String evidenceRef) { }
    enum Outcome { SERVICE_READY, FAILED_NO_EFFECT, UNKNOWN }
}
