package cn.jia.agent.output.dao;

import java.util.List;

public interface OutputUploadDao {
    record ScopeQuota(long reservedBytes, long storedBytes, long maxBytes,
                      int activeUploads, int maxActiveUploads) { }
    record BindingQuota(int activeUploads, int maxActiveUploads) { }
    record RunQuota(long uploadRequests, long maxUploadRequests,
                    long attemptBytes, long maxAttemptBytes) { }
    record Receipt(byte[] requestHash, int httpStatus, String responseJson) { }
    record UploadRow(String tenantId, String clientId, String uploadId, String runId,
                     String bindingId, String objectId, String fileName, long expectedSize,
                     byte[] expectedSha256, String declaredMime, String state, long writerEpoch,
                     Long writerStartedAt, Long writerUntil, Long writerDeadlineAt, long expiresAt,
                     long reservedBytes, boolean slotReleased, int verificationAttempts,
                     Long verificationNextAt, String verificationLeaseOwner,
                     Long verificationLeaseUntil, String errorCode) { }
    record ObjectRow(String tenantId, String clientId, String objectId, String runId,
                     String bucket, String storageKey, String storageVersion,
                     byte[] actualSha256, Long actualSize, String actualMime,
                     String verificationStatus, String lifecycleStatus, String scanEngineVersion,
                     Long verifiedAt, Long deleteAfter, Long deletedAt, int deleteAttempts,
                     Long deleteNextAt, String deleteLeaseOwner, Long deleteLeaseUntil,
                     String errorCode) { }
    record CleanupRow(byte[] cleanupId, String tenantId, String clientId, String objectId,
                      String uploadId, long writerEpoch, String bucket, String storageKey,
                      String storageVersion, String state, String quotaChargeKind,
                      long quotaChargeBytes, long safeAfter, int attempts, long nextAttemptAt,
                      String leaseOwner, Long leaseUntil) { }

    void ensureScopeQuota(String tenantId, String clientId, long maxBytes, int maxActive, long now);
    ScopeQuota lockScopeQuota(String tenantId, String clientId);
    int changeScopeQuota(String tenantId, String clientId, long reservedDelta,
                         long storedDelta, int activeDelta, long now);
    void ensureBindingQuota(String tenantId, String clientId, String bindingId, int maxActive, long now);
    BindingQuota lockBindingQuota(String tenantId, String clientId, String bindingId);
    int changeBindingQuota(String tenantId, String clientId, String bindingId, int activeDelta, long now);
    void ensureRunQuota(String tenantId, String clientId, String runId,
                        long maxRequests, long maxAttemptBytes, long now);
    RunQuota lockRunQuota(String tenantId, String clientId, String runId);
    int changeRunQuota(String tenantId, String clientId, String runId,
                       long requestDelta, long attemptBytesDelta, long now);
    int activeRunFiles(String tenantId, String clientId, String runId);
    long activeRunBytes(String tenantId, String clientId, String runId);

    Receipt lockReceipt(String tenantId, String clientId, String actorKind, String actorId,
                        String operation, String idempotencyKey);
    int insertReceipt(String tenantId, String clientId, String actorKind, String actorId,
                      String operation, String idempotencyKey, byte[] requestHash,
                      int httpStatus, String responseJson, long retainUntil, long now);

    int insertObject(ObjectRow row, long now);
    int insertUpload(UploadRow row, long now);
    UploadRow findUpload(String tenantId, String clientId, String uploadId, boolean forUpdate);
    ObjectRow findObject(String tenantId, String clientId, String objectId, boolean forUpdate);
    int beginWriter(String tenantId, String clientId, String uploadId, long expectedEpoch,
                    long newEpoch, long startedAt, long writerUntil, long deadlineAt);
    int renewWriter(String tenantId, String clientId, String uploadId, long epoch,
                    long now, long writerUntil);
    int failWriter(String tenantId, String clientId, String uploadId, long epoch, long now);
    int recordUploadedObject(String tenantId, String clientId, String uploadId, long epoch,
                             String objectId, String storageKey, String storageVersion,
                             byte[] actualSha256, long actualSize, String actualMime, long now);
    int markVerifying(String tenantId, String clientId, String uploadId, long now);
    int markReady(String tenantId, String clientId, String uploadId, String objectId,
                  String scanVersion, long now);
    int markRejected(String tenantId, String clientId, String uploadId, String objectId,
                     String errorCode, long now);
    int releaseUploadSlot(String tenantId, String clientId, String uploadId, long now);
    int markExpiredWithoutWriter(String tenantId, String clientId, String uploadId,
                                 String objectId, long now);

    int insertCleanup(CleanupRow row, long now);
    int abandonCleanup(String tenantId, String clientId, String uploadId, long epoch,
                       String chargeKind, long chargeBytes, long safeAfter, long now);
    int retainCleanup(String tenantId, String clientId, String uploadId, long epoch,
                      String storageVersion, long now);
    int countOpenCleanup(String tenantId, String clientId, String uploadId);
    List<CleanupRow> findDueCleanup(long now, int limit);
    CleanupRow findCleanup(byte[] cleanupId, String tenantId, String clientId, boolean forUpdate);
    CleanupRow findCleanupByUploadEpoch(String tenantId, String clientId, String uploadId,
                                        long writerEpoch, boolean forUpdate);
    int claimCleanup(byte[] cleanupId, String tenantId, String clientId,
                     String owner, long leaseUntil, long now);
    int finishCleanup(byte[] cleanupId, String tenantId, String clientId,
                      String owner, long now);
    int finishRejectedObjectForCleanup(String tenantId, String clientId, String objectId,
                                       String storageKey, String storageVersion, long now);
    int retryCleanup(byte[] cleanupId, String tenantId, String clientId,
                     String owner, long nextAttemptAt, String errorCode, long now);

    List<UploadRow> findDueVerification(long now, int limit);
    int claimVerification(String tenantId, String clientId, String uploadId,
                          String owner, long leaseUntil, long now);
    int retryVerification(String tenantId, String clientId, String uploadId,
                          String owner, long nextAttemptAt, String errorCode, long now);
    List<UploadRow> findExpiredUploads(long now, int limit);

    List<ObjectRow> findDueObjects(long now, int limit);
    int claimObjectDelete(String tenantId, String clientId, String objectId,
                          String owner, long leaseUntil, long now);
    long activeObjectReferences(String tenantId, String clientId, String objectId, long now);
    int finishObjectDelete(String tenantId, String clientId, String objectId, String owner, long now);
    int retryObjectDelete(String tenantId, String clientId, String objectId, String owner,
                          long nextAt, String errorCode, long now);
}
