package cn.jia.agent.service;

import java.util.List;

/**
 * Atomic current-session observation for the controlled-image execution sibling and credential
 * binding. Implementations must derive both declarations from the same authenticated live session.
 */
public interface ControlledImageExecutionSessionLookup {
    Snapshot current(Scope scope);

    record Scope(String tenantId, String clientId, String ownerJiacn,
            String canonicalAgentId) { }

    record Snapshot(State state, String runtimeInstanceId, Integer schemaVersion,
            String transport, List<String> supportedOperations, String providerLane,
            String bindingId, Long bindingEpoch, String modelId, Integer maxInputItems,
            Integer maxOutboundRequestAttempts, Integer precallFenceVersion) {
        public Snapshot {
            supportedOperations = supportedOperations == null ? List.of()
                    : List.copyOf(supportedOperations);
        }
        public boolean matchesRuntime(String expectedRuntimeInstanceId) {
            return state == State.READY && expectedRuntimeInstanceId != null
                    && expectedRuntimeInstanceId.equals(runtimeInstanceId);
        }
    }

    enum State { READY, OFFLINE, UNDECLARED, DISABLED, UNSUPPORTED, AMBIGUOUS, MISMATCHED }

    final class SourceUnavailable extends RuntimeException {
        public SourceUnavailable(Throwable cause) {
            super("CONTROLLED_IMAGE_EXECUTION_SOURCE_UNAVAILABLE", cause);
        }
    }
}
