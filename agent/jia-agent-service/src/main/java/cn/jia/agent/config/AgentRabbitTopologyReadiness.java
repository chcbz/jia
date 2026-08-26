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
                Status.NOT_CHECKED, Source.NONE, Coverage.NONE,
                manifestSha256, 0L, null));
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    void markProvisioned() {
        update(Status.READY, Source.PROVISION, Coverage.CANONICAL_TOPOLOGY, null);
    }

    void markExistenceConfirmed() {
        update(Status.EXISTENCE_CONFIRMED, Source.PASSIVE_VERIFY,
                Coverage.RESOURCE_EXISTENCE, null);
    }

    void markFailed(Source source, Throwable failure) {
        String failureType = failure == null ? "unknown" : failure.getClass().getName();
        update(Status.FAILED, source, Coverage.NONE, failureType);
    }

    private void update(
            Status status,
            Source source,
            Coverage coverage,
            String failureType) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(coverage, "coverage");
        snapshot.updateAndGet(previous -> new Snapshot(
                status, source, coverage, manifestSha256,
                previous.revision() + 1L, failureType));
    }

    public enum Status {
        NOT_CHECKED,
        EXISTENCE_CONFIRMED,
        READY,
        FAILED
    }

    /** Operation that produced the current snapshot. */
    public enum Source {
        NONE,
        PROVISION,
        PASSIVE_VERIFY
    }

    /** Broker facts established by the source operation. */
    public enum Coverage {
        NONE,
        RESOURCE_EXISTENCE,
        CANONICAL_TOPOLOGY
    }

    public record Snapshot(
            Status status,
            Source source,
            Coverage coverage,
            String manifestSha256,
            long revision,
            String failureType) {
        /** Publish-safe canonical readiness can only originate from successful provisioning. */
        public boolean canonicalTopologyReady() {
            return status == Status.READY
                    && source == Source.PROVISION
                    && coverage == Coverage.CANONICAL_TOPOLOGY;
        }
    }
}
