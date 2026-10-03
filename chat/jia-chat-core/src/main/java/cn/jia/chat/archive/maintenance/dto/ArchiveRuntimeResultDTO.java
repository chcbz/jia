package cn.jia.chat.archive.maintenance.dto;

/** Authoritative request-scoped run receipt; transport ACKs are intentionally absent. */
public record ArchiveRuntimeResultDTO(
        String jobId, String runId, String commandId, String attempt, String executionEpoch,
        String runState, String runRevision, String jobState, String jobRevision, String stage,
        String validationId, String validationOutcome, String validationDigest, String draftRevision,
        String publicationId, String workId, String editionId, String publicationState,
        String failurePhase, String failureCode, Boolean failureRetryable,
        String failureId, Boolean blockedRootCause, String failureDiagnostic) {
    public ArchiveRuntimeResultDTO(String jobId, String runId, String commandId, String attempt,
            String executionEpoch, String runState, String runRevision, String jobState,
            String jobRevision, String stage, String validationId, String validationOutcome,
            String validationDigest, String draftRevision, String publicationId, String workId,
            String editionId, String publicationState, String failurePhase, String failureCode,
            Boolean failureRetryable) {
        this(jobId, runId, commandId, attempt, executionEpoch, runState, runRevision, jobState,
                jobRevision, stage, validationId, validationOutcome, validationDigest,
                draftRevision, publicationId, workId, editionId, publicationState, failurePhase,
                failureCode, failureRetryable, null, null, null);
    }
}
