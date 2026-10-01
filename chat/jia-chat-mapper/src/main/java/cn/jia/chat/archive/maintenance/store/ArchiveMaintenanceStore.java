package cn.jia.chat.archive.maintenance.store;

import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.dto.ArchiveJobEventDTO;
import java.util.List;

public interface ArchiveMaintenanceStore {
    ArchiveManagerGrantRecord findManagerGrant(ArchiveActorScope actor, String collectionId, boolean lock);
    /** Locks every v1 configuration-owned manager row in deterministic primary-key order. */
    List<ArchiveManagerGrantRecord> lockManagerGrants();
    void insertManagerGrant(ArchiveManagerGrantRecord grant);
    int activateConfiguredManagerGrant(ArchiveManagerGrantRecord grant, long expectedRevision);
    int revokeManagerGrant(ArchiveActorScope actor, String collectionId, long expectedRevision);

    Slot findSlot(String collectionId, String roleCode);
    Slot lockSlot(String collectionId, String roleCode);
    void ensureSlot(String collectionId, String roleCode);
    ArchiveAppointmentRecord findAppointment(String appointmentId, boolean lock);
    ArchiveAppointmentRecord findCurrentAppointment(String collectionId, boolean lock);
    List<ArchiveAppointmentRecord> listAppointments(ArchiveActorScope actor, String collectionId);
    void insertAppointment(ArchiveAppointmentRecord appointment);
    int activateSlot(String collectionId, String roleCode, long expectedRevision, String appointmentId);
    int revokeAppointment(String appointmentId, long expectedRevision);
    int clearSlot(String collectionId, String roleCode, String appointmentId, long expectedRevision);

    ArchiveSourceSnapshotRecord findSource(String sourceId);
    void insertSource(ArchiveSourceSnapshotRecord source);

    ArchiveConfirmedRequestRecord findConfirmedRequest(ArchiveActorScope actor,
                                                        String confirmationRef, boolean lock);
    void insertConfirmedRequest(ArchiveConfirmedRequestRecord confirmation);
    int bindConfirmedRequest(ArchiveActorScope actor, String confirmationRef, long expectedRevision,
                             String conversationId, String canonicalMessageId,
                             long conversationGeneration, String turnSha256,
                             String entryPoint, String targetAgentId);
    ArchiveMaintenanceJobRecord findJob(String jobId, boolean lock);
    ArchiveMaintenanceJobRecord findJobByIntent(ArchiveActorScope actor, String requestIntentId, boolean lock);
    List<ArchiveMaintenanceJobRecord> listJobs(ArchiveActorScope actor, String collectionId, int limit);
    void insertJob(ArchiveMaintenanceJobRecord job);
    void insertRun(String runId, String jobId, long executionEpoch, long grantRevision);
    ArchiveJobRunRecord findRun(String runId, boolean lock);
    int activateRun(String runId, long expectedRevision, String runtimeInstanceId);
    int startRun(String runId, long expectedRevision, String messageId);
    int failRun(String runId, long expectedRevision, String phase, String code, boolean retryable);
    int completeRun(String runId, long expectedRevision);
    ArchiveExecutionGrantRecord findExecutionGrant(String runId, boolean lock);
    List<ArchiveExecutionGrantRecord> listActiveExecutionGrants(String appointmentId, boolean lock);
    List<String> lockRunIdsForAppointment(String appointmentId);
    List<String> lockRunIdsForManager(ArchiveActorScope actor, String collectionId);
    List<ManagerRunTarget> listUnfencedRunTargetsForManager(ArchiveActorScope actor, String collectionId, boolean lock);
    void insertExecutionGrant(ArchiveExecutionGrantRecord grant);
    int releaseExecutionGrant(String runId, long expectedRevision);
    int fenceRun(String runId);
    int updateJobState(String jobId, long expectedRevision, String state, String waitReason,
                       String publicationId);
    int replaceCurrentRun(String jobId, long expectedRevision, String expectedRunId, String newRunId,
                          ArchiveAppointmentRecord appointment, long managerAuthorizationRevision,
                          String state, String waitReason);

    ArchiveDraftRecord findDraftByJob(String jobId, boolean lock);
    void insertDraft(ArchiveDraftRecord draft);
    int updateDraft(String draftId, long expectedRevision, long newRevision, String state,
                    String contentJson, String contentSha256, Long validatedRevision, String validationId);
    void insertValidation(ArchiveValidationRecord validation);
    ArchiveValidationRecord findValidation(String validationId);
    ArchiveValidationRecord findCurrentValidation(String draftId, long draftRevision);

    CollectionWork lockCollectionWork(String collectionId, String workId);
    CollectionWork findCollectionWork(String collectionId, String workId);
    void insertCollectionWork(String collectionId, String workId, String canonicalKey);
    int bumpCollectionWork(String collectionId, String workId, long expectedRevision);
    void insertPublication(ArchivePublicationRecord publication);
    ArchivePublicationRecord findPublicationByJob(String jobId);

    void appendJobEvent(String jobId, long jobRevision, String eventType, String dataJson);
    List<ArchiveJobEventRecord> listJobEvents(String jobId, long afterSequence, int limit);

    Operation findOperation(ArchiveActorScope actor, String key);
    Operation beginOperation(ArchiveActorScope actor, String key, String method, String path,
                             String requestSha256, String targetType, String targetId);
    void commitOperation(ArchiveActorScope actor, String key, String targetId);

    record Slot(String collectionId, String roleCode, String currentAppointmentId, long revision) { }
    record CollectionWork(String collectionId, String workId, String canonicalKey, long revision) { }
    record ManagerRunTarget(String runId, String agentId, String bindingVersion) { }
    record Operation(boolean created, String httpMethod, String canonicalPath, String requestSha256,
                     String targetType, String targetId, String state) { }
}
