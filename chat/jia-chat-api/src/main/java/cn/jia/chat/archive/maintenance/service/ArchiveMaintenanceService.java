package cn.jia.chat.archive.maintenance.service;

import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.model.ArchiveRuntimeScope;
import cn.jia.chat.archive.maintenance.model.ArchiveRequestContext;
import java.util.List;
import java.util.function.Supplier;

public interface ArchiveMaintenanceService {
    ArchiveSourceSnapshotDTO prepareSource(ArchiveActorScope actor,String collectionId,String operationKey,ArchiveSourcePrepareRequest request);
    ArchiveSourceSnapshotDTO source(ArchiveActorScope actor,String sourceId);
    byte[] runtimeSourceContent(ArchiveRuntimeScope runtime,String jobId,String runId,String sourceId);
    ArchiveCapabilitiesDTO capabilities(ArchiveActorScope actor,String collectionId);
    ArchiveSlotDTO slot(ArchiveActorScope actor,String collectionId);
    ArchiveWorkStateDTO workState(ArchiveActorScope actor,String collectionId,String workId);
    ArchiveEditionHistoryDTO editionHistory(ArchiveActorScope actor,String workId);
    ArchiveEditionVersionDTO edition(ArchiveActorScope actor,String workId,String editionId);
    ArchiveWithdrawalDTO withdraw(ArchiveActorScope actor,String workId,String editionId,String operationKey,long expectedWorkRevision,ArchiveWithdrawRequest request);
    List<ArchiveAppointmentDTO> appointments(ArchiveActorScope actor,String collectionId);
    ArchiveAppointmentDTO createAppointment(ArchiveActorScope actor,String collectionId,String operationKey,long expectedSlotRevision,ArchiveAppointmentCreateRequest request);
    ArchiveAppointmentDTO revokeAppointment(ArchiveActorScope actor,String appointmentId,String operationKey,long expectedRevision,ArchiveAppointmentRevokeRequest request);
    ArchiveOperationDTO revokeManagerAuthorization(ArchiveActorScope actor,String collectionId,String operationKey,long expectedRevision,ArchiveManagerRevokeRequest request);
    ArchiveJobDTO createJob(ArchiveActorScope actor,String collectionId,String operationKey,ArchiveJobCreateRequest request);
    ArchiveMaintenanceRequestResultDTO confirmRequest(ArchiveActorScope actor, String collectionId,
            String operationKey, ArchiveMaintenanceRequest request);
    ArchiveRequestContext bindChatConfirmation(ArchiveActorScope actor, String confirmationRef,
            String conversationId, long conversationGeneration, String turnSha256,
            String entryPoint, String targetAgentId, Supplier<String> canonicalMessageIdWriter);
    ArchiveMaintenanceRequestResultDTO request(ArchiveRequestContext context, ArchiveMaintenanceRequest request);
    ArchiveJobDTO getJob(ArchiveActorScope actor,String jobId);
    ArchiveJobDTO resolveInput(ArchiveActorScope actor,String jobId,String operationKey,long expectedJobRevision,ArchiveResolveInputRequest request);
    ArchiveRecoveryContextDTO recoveryContext(ArchiveActorScope actor,String jobId);
    ArchiveExecutionDTO ensureExecution(ArchiveActorScope actor,String jobId,String operationKey,long expectedJobRevision);
    ArchiveExecutionRecoveryDTO resume(ArchiveActorScope actor,String jobId,String operationKey,long expectedJobRevision,ArchiveResumeRequest request);
    ArchiveExecutionRecoveryDTO reassign(ArchiveActorScope actor,String jobId,String operationKey,long expectedJobRevision,ArchiveReassignRequest request);
    List<ArchiveJobEventDTO> jobEvents(ArchiveActorScope actor,String jobId,long afterSequence,int limit);
    List<ArchiveJobDTO> listJobs(ArchiveActorScope actor,String collectionId,int limit);
    ArchiveDraftDTO getDraft(ArchiveActorScope actor,String jobId);
    ArchiveDraftDTO updateDraft(ArchiveActorScope actor,String jobId,String operationKey,long expectedRevision,ArchiveDraftUpdateRequest request);
    ArchiveValidationDTO validate(ArchiveActorScope actor,String jobId,String operationKey,long expectedDraftRevision);
    ArchiveValidationDTO validation(ArchiveActorScope actor,String draftId);
    ArchiveOperationDTO operationByKey(ArchiveActorScope actor,String operationKey);
    ArchiveJobDTO cancel(ArchiveActorScope actor,String jobId,String operationKey,long expectedJobRevision,ArchiveCancelRequest request);
    ArchivePublicationDTO publish(ArchiveActorScope actor,String jobId,String operationKey,long expectedDraftRevision,ArchivePublishRequest request);

    ArchiveRuntimeResultDTO runtimeStart(ArchiveRuntimeScope runtime,String jobId,String runId,ArchiveRuntimeStartRequest request);
    ArchiveRuntimeResultDTO runtimeFailure(ArchiveRuntimeScope runtime,String jobId,String runId,ArchiveRuntimeFailureRequest request);
    ArchiveRuntimeResultDTO runtimeResult(ArchiveRuntimeScope runtime,String jobId,String runId);
    ArchiveRuntimeContextDTO runtimeContext(ArchiveRuntimeScope runtime,String jobId,String runId);
    ArchiveDraftDTO runtimeDraft(ArchiveRuntimeScope runtime,String jobId,String runId);
    ArchiveDraftDTO runtimeUpdateDraft(ArchiveRuntimeScope runtime,String jobId,String runId,String operationKey,long expectedRevision,ArchiveDraftUpdateRequest request);
    ArchiveDraftDTO runtimePutBlock(ArchiveRuntimeScope runtime,String jobId,String runId,String blockKey,String operationKey,long expectedRevision,ArchiveDraftBlockInput request);
    ArchiveValidationDTO runtimeValidate(ArchiveRuntimeScope runtime,String jobId,String runId,String operationKey,long expectedDraftRevision);
    ArchiveValidationDTO runtimeValidation(ArchiveRuntimeScope runtime,String jobId,String runId);
    ArchivePublicationDTO runtimePublish(ArchiveRuntimeScope runtime,String jobId,String runId,String operationKey,long expectedDraftRevision,ArchivePublishRequest request);
}
