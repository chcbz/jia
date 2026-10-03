package cn.jia.chat.archive.maintenance.model;

import java.time.Instant;

public record ArchiveExecutionFailureRecord(long failureId, String jobId, String runId,
        long attempt, String inputFingerprint, String rootCauseFingerprint,
        String phase, String code, boolean retryable, String diagnostic,
        String runtimeInstanceId, String registrationFingerprint,
        String installationRef, long installationRevision,
        String skillKey, String skillVersion, String packageSha256,
        String sourceVerificationState, String sourceVerificationFingerprint,
        boolean blockedRootCause, String repairResolutionCode,
        String repairEvidenceFingerprint,
        String resolvedByTenantId, String resolvedByClientId, String resolvedByOwnerJiacn,
        Long resolutionManagerRevision, Instant resolvedAt, Instant failedAt) { }
