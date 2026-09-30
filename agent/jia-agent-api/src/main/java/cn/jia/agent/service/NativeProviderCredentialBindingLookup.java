package cn.jia.agent.service;

/**
 * Read-only process-local observation of one current authenticated controlled-provider binding.
 * Session IDs, credentials, token digests and authentication epochs are deliberately absent.
 */
public interface NativeProviderCredentialBindingLookup {
    Snapshot current(Scope scope);

    record Scope(String tenantId, String clientId, String ownerJiacn,
            String canonicalAgentId) { }

    record Snapshot(State state, Integer schemaVersion, String providerLane,
            String bindingId, Long bindingEpoch, String modelId, Integer maxInputItems,
            Integer maxOutboundRequestAttempts, Integer precallFenceVersion) { }

    enum State { READY, OFFLINE, UNDECLARED, DISABLED, UNSUPPORTED, AMBIGUOUS }

    /** Authoritative identity/authentication storage could not be read. */
    final class SourceUnavailable extends RuntimeException {
        public SourceUnavailable(Throwable cause) {
            super("NATIVE_PROVIDER_BINDING_SOURCE_UNAVAILABLE", cause);
        }
    }
}
