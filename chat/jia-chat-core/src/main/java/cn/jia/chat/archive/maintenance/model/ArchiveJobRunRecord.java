package cn.jia.chat.archive.maintenance.model;

public record ArchiveJobRunRecord(String runId, String jobId, long executionEpoch,
        String runtimeInstanceId, long grantRevision, String startedMessageId,
        String failurePhase, String failureCode, Boolean failureRetryable,
        String state, long revision) {
    public ArchiveJobRunRecord(String runId, String jobId, long executionEpoch,
            String runtimeInstanceId, long grantRevision, String state, long revision) {
        this(runId, jobId, executionEpoch, runtimeInstanceId, grantRevision,
                null, null, null, null, state, revision);
    }
}
