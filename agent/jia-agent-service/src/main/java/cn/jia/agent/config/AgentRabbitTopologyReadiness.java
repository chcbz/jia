package cn.jia.agent.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit-operation readiness for the D04 topology; construction never probes RabbitMQ. */
public final class AgentRabbitTopologyReadiness {
    private final String manifestSha256;
    private final AtomicReference<Snapshot> snapshot;

    AgentRabbitTopologyReadiness(AgentRabbitTopologyManifest manifest) {
        manifestSha256 = Objects.requireNonNull(manifest, "manifest").sha256();
        snapshot = new AtomicReference<>(new Snapshot(
                Status.NOT_CHECKED, Operation.NONE, manifestSha256, 0L, null));
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    void markReady(Operation operation) {
        update(Status.READY, operation, null);
    }

    void markFailed(Operation operation, Throwable failure) {
        String failureType = failure == null ? "unknown" : failure.getClass().getName();
        update(Status.FAILED, operation, failureType);
    }

    private void update(Status status, Operation operation, String failureType) {
        Objects.requireNonNull(operation, "operation");
        snapshot.updateAndGet(previous -> new Snapshot(
                status, operation, manifestSha256, previous.revision() + 1L, failureType));
    }

    public enum Status {
        NOT_CHECKED,
        READY,
        FAILED
    }

    public enum Operation {
        NONE,
        PROVISION,
        PASSIVE_VERIFY
    }

    public record Snapshot(
            Status status,
            Operation operation,
            String manifestSha256,
            long revision,
            String failureType) {
    }
}
