package cn.jia.agent.service;

import java.util.List;

/**
 * Read-only process-local evidence for the currently authenticated native bounty executor.
 * Implementations must not expose session IDs, credentials, token digests or authentication epochs.
 */
public interface NativeBountyExecutionSessionLookup {
    Snapshot current(Scope scope);

    record Scope(String tenantId, String clientId, String ownerJiacn, String canonicalAgentId) { }

    record Snapshot(State state, Integer schemaVersion, String transport,
            List<String> supportedOperations) {
        public Snapshot {
            supportedOperations = supportedOperations == null
                    ? List.of() : List.copyOf(supportedOperations);
        }
    }

    enum State { READY, OFFLINE, UNDECLARED, DISABLED, UNSUPPORTED, AMBIGUOUS }

    /** Authoritative identity/authentication storage could not be read. */
    final class SourceUnavailable extends RuntimeException {
        public SourceUnavailable(Throwable cause) { super("NATIVE_BOUNTY_CAPABILITY_SOURCE_UNAVAILABLE", cause); }
    }
}
