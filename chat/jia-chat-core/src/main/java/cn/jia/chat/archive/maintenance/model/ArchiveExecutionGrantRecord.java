package cn.jia.chat.archive.maintenance.model;

public record ArchiveExecutionGrantRecord(
        String grantRef, String runId, String tenantId, String clientId, String ownerJiacn,
        String appointmentId, long appointmentRevision, long managerAuthorizationRevision,
        String agentId, long bindingVersion,
        String executionRef, String commandId, int activeAttempt, long executionEpoch,
        String runtimeInstanceId, byte[] registrationHash, String skillOrigin,
        String installationRef, long installationRevision, String skillKey, String skillVersion,
        String packageSha256, String dispatchKey, String requestSha256, String contextRef,
        long expiresAt, String state, long revision) {
    public ArchiveExecutionGrantRecord { registrationHash = registrationHash == null ? null : registrationHash.clone(); }
    @Override public byte[] registrationHash() { return registrationHash == null ? null : registrationHash.clone(); }
}
