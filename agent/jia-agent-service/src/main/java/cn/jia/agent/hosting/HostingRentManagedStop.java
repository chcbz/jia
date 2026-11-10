package cn.jia.agent.hosting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only the process-stop phase of FINISHING. Never starts a Runtime, settles tasks or clears private data. */
@Service
public final class HostingRentManagedStop {
    private final ManagedHostingProvisioner runtime;
    private final HostingRentHandoverCompletion relationships;
    public HostingRentManagedStop(ManagedHostingProvisioner runtime, HostingRentHandoverCompletion relationships) {
        this.runtime=runtime;this.relationships=relationships;
    }
    /** Original INITIAL relationship from the lifecycle owner's durable snapshot. Null means not confirmed,
     * never permission to clean/release. Lease identity is the stable handover ID across transport retries. */
    public ManagedHostingProvisioner.CurrentOperation stop(ManagedHostingProvisioner.Preparation initial) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Managed process stop must run outside database transactions");
        var current=runtime.currentOperation(initial);
        if (current==null || !relationships.prepareManagedStop(current)) return null;
        var p=current.preparation();
        if (!runtime.quiesce(p,current.candidate(),p.leaseId())) return null;
        // A lost DB commit is retried through the same Runtime handover, never a new operation.
        relationships.recordManagedStop(current);
        return current;
    }
}
