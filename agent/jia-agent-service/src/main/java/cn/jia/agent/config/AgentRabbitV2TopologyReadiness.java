package cn.jia.agent.config;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Scope-exact bounded readiness registry; passive declarations never establish readiness. */
public final class AgentRabbitV2TopologyReadiness {
    static final int MAX_REGISTERED_SCOPES = 256;

    private final ConcurrentMap<AgentRabbitV2TopologyManifest.Scope, Snapshot> snapshots =
            new ConcurrentHashMap<>();
    private final Object registrationLock = new Object();
    private final int maxRegisteredScopes;

    public AgentRabbitV2TopologyReadiness() {
        this(MAX_REGISTERED_SCOPES);
    }

    AgentRabbitV2TopologyReadiness(int maxRegisteredScopes) {
        if (maxRegisteredScopes <= 0 || maxRegisteredScopes > MAX_REGISTERED_SCOPES) {
            throw new IllegalArgumentException("maxRegisteredScopes is out of range");
        }
        this.maxRegisteredScopes = maxRegisteredScopes;
    }

    public Snapshot snapshot(AgentRabbitV2TopologyManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        Snapshot current = snapshots.get(manifest.scope());
        if (current == null) {
            return new Snapshot(Status.NOT_CHECKED, Source.NONE, Coverage.NONE,
                    manifest.scope(), manifest.sha256(), 0L, null);
        }
        if (!manifest.sha256().equals(current.manifestSha256())) {
            return new Snapshot(Status.FAILED, Source.NONE, Coverage.NONE,
                    manifest.scope(), manifest.sha256(), current.revision() + 1L,
                    "MANIFEST_DIGEST_MISMATCH");
        }
        return current;
    }

    void register(AgentRabbitV2TopologyManifest manifest) {
        synchronized (registrationLock) {
            registerLocked(Objects.requireNonNull(manifest, "manifest"));
        }
    }

    void markProvisioned(AgentRabbitV2TopologyManifest manifest) {
        update(manifest, Status.READY, Source.PROVISION, Coverage.CANONICAL_TOPOLOGY, null);
    }

    void markExistenceConfirmed(AgentRabbitV2TopologyManifest manifest) {
        update(manifest, Status.EXISTENCE_CONFIRMED, Source.PASSIVE_VERIFY,
                Coverage.RESOURCE_EXISTENCE, null);
    }

    void markFailed(AgentRabbitV2TopologyManifest manifest, Source source, Throwable failure) {
        update(manifest, Status.FAILED, source, Coverage.NONE,
                failure == null ? "unknown" : failure.getClass().getName());
    }

    private void update(AgentRabbitV2TopologyManifest manifest, Status status, Source source,
                        Coverage coverage, String failureType) {
        synchronized (registrationLock) {
            Snapshot previous = registerLocked(Objects.requireNonNull(manifest, "manifest"));
            AgentRabbitV2TopologyManifest.Scope scope = manifest.scope();
            if (!manifest.sha256().equals(previous.manifestSha256())) {
                snapshots.put(scope, new Snapshot(Status.FAILED, source, Coverage.NONE, scope,
                        manifest.sha256(), previous.revision() + 1L,
                        "MANIFEST_DIGEST_MISMATCH"));
                return;
            }
            snapshots.put(scope, new Snapshot(status, source, coverage, scope,
                    manifest.sha256(), previous.revision() + 1L, failureType));
        }
    }

    private Snapshot registerLocked(AgentRabbitV2TopologyManifest manifest) {
        Snapshot previous = snapshots.get(manifest.scope());
        if (previous != null) return previous;
        if (snapshots.size() >= maxRegisteredScopes) {
            throw new ScopeCapacityExceededException();
        }
        Snapshot registered = new Snapshot(Status.NOT_CHECKED, Source.NONE, Coverage.NONE,
                manifest.scope(), manifest.sha256(), 0L, null);
        snapshots.put(manifest.scope(), registered);
        return registered;
    }

    int registeredScopeCount() {
        return snapshots.size();
    }

    static final class ScopeCapacityExceededException extends IllegalStateException {
        private ScopeCapacityExceededException() {
            super("Agent Rabbit v2 topology scope capacity exceeded", null);
        }
    }

    public enum Status { NOT_CHECKED, EXISTENCE_CONFIRMED, READY, FAILED }
    public enum Source { NONE, PROVISION, PASSIVE_VERIFY }
    public enum Coverage { NONE, RESOURCE_EXISTENCE, CANONICAL_TOPOLOGY }

    public record Snapshot(Status status, Source source, Coverage coverage,
                           AgentRabbitV2TopologyManifest.Scope scope,
                           String manifestSha256, long revision, String failureType) {
        public boolean canonicalTopologyReady(AgentRabbitV2TopologyManifest manifest) {
            return manifest != null && scope.equals(manifest.scope())
                    && manifestSha256.equals(manifest.sha256())
                    && status == Status.READY && source == Source.PROVISION
                    && coverage == Coverage.CANONICAL_TOPOLOGY;
        }
    }
}
