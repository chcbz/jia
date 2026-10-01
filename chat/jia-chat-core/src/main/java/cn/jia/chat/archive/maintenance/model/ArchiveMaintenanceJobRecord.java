package cn.jia.chat.archive.maintenance.model;

public record ArchiveMaintenanceJobRecord(String jobId, String runId, String collectionId,
        String tenantId, String clientId, String ownerJiacn, String appointmentId,
        Long appointmentRevision, String agentId, String bindingVersion, String permissionProfile,
        long managerAuthorizationRevision, String publicationMode, String operation, String workId, String canonicalKey, String title,
        String sourceId, String sourceSha256, String sourceSummary, String rightsBasis, String state, String waitReason,
        long revision, String draftId, String publicationId, String requestIntentId, String requestSha256,
        String targetAgentId) {

    public ArchiveMaintenanceJobRecord(String jobId, String runId, String collectionId,
            String tenantId, String clientId, String ownerJiacn, String appointmentId,
            long appointmentRevision, String agentId, String bindingVersion, String permissionProfile,
            long managerAuthorizationRevision, String publicationMode, String operation, String workId,
            String canonicalKey, String title, String sourceId, String sourceSha256,
            String sourceSummary, String rightsBasis, String state, String waitReason,
            long revision, String draftId, String publicationId, String requestIntentId,
            String requestSha256) {
        this(jobId, runId, collectionId, tenantId, clientId, ownerJiacn, appointmentId,
                appointmentRevision, agentId, bindingVersion, permissionProfile,
                managerAuthorizationRevision, publicationMode, operation, workId, canonicalKey,
                title, sourceId, sourceSha256, sourceSummary, rightsBasis, state, waitReason,
                revision, draftId, publicationId, requestIntentId, requestSha256, null);
    }
}
