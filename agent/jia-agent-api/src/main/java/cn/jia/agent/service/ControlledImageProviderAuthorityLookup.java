package cn.jia.agent.service;

/** Read-only server operator delegation projection; it never issues or consumes consent. */
public interface ControlledImageProviderAuthorityLookup {
    Snapshot current(Scope scope,String targetAgentId,String bindingId,long bindingEpoch);
    record Scope(String tenantId,String clientId,String ownerJiacn) { }
    record Snapshot(State state,String providerLane,String bindingId,String bindingEpoch,
            String modelId,Integer maxOutboundRequestAttempts) { }
    enum State { READY,UNAVAILABLE }
    final class SourceUnavailable extends RuntimeException {
        public SourceUnavailable(Throwable cause) { super("CONTROLLED_IMAGE_OPERATOR_SOURCE_UNAVAILABLE",cause); }
    }
}
