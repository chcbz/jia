package cn.jia.agent.platform;

/** Platform-owned persistence; it has no dependency on money/order/entitlement tables. */
public interface PlatformInstallationStore {
    record Scope(String tenant,String client,String owner) { }
    record ScanCursor(long createdAt,String installationId) {
        public ScanCursor {
            if(createdAt<0 || installationId==null || !installationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))
                throw new IllegalArgumentException("Invalid platform installation scan cursor");
        }
    }
    record Installation(String id,Scope scope,String actorId,String requestKey,String requestSha,
            String agentId,long bindingId,String runtimeInstanceId,byte[] registrationHash,
            String skillKey,String skillVersion,String packageSha,String challengeId,String commandId,
            String state,String resultSha,String errorCode,long revision,long createdAt) {
        public Installation { registrationHash=registrationHash.clone(); }
        @Override public byte[] registrationHash() { return registrationHash.clone(); }
        public ScanCursor scanCursor() { return new ScanCursor(createdAt,id); }
    }
    record ResolutionKey(Scope scope,String origin,String agentId,String skillKey,String skillVersion,
            String packageSha,long bindingId,String runtimeInstanceId,byte[] registrationHash) {
        public ResolutionKey { registrationHash=registrationHash.clone(); }
        @Override public byte[] registrationHash() { return registrationHash.clone(); }
    }
    record ResolutionCandidate(Installation installation,String deliveryCommandType,String deliveryStatus,
            Long deliveryExpiresAt) { }
    java.util.List<Installation> terminalDeliveryCandidates(ScanCursor after,long now,int limit);
    ResolutionCandidate verifiedCandidate(ResolutionKey key);
    ResolutionCandidate pendingCandidate(ResolutionKey key,long now);
    Installation latestHistorical(ResolutionKey key);
    void lockScope(Scope scope);
    Installation byKey(Scope scope,String actorId,String key);
    Installation find(Scope scope,String id,boolean lock);
    void insert(Installation installation);
    int finish(Scope scope,String id,long revision,String outcome,String resultSha,String errorCode);
}
