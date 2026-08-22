package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;

import java.time.Instant;
import java.util.List;

/**
 * H05A durable boundary. Creation locks in the frozen order mutation -> active edition/content ->
 * exact-scoped question -> outbox; retry/worker paths use mutation (when present) -> question -> outbox.
 * Event sequence allocation occurs only while the question row is locked and commits with its event row.
 * Publication reads persisted events after commit and never calls a provider.
 */
public interface ArchiveQuestionStore {
    MutationRecord insertOrLockMutation(ArchiveOwnerScope owner, String questionId, String method,
                                        String canonicalPath, String key, String requestSha256, Instant expiresAt);
    int completeMutation(ArchiveOwnerScope owner, long mutationRowId, String requestSha256,
                         long questionRowId, int status, String contentType, byte[] responseBody);

    long countRecentQuestions(ArchiveOwnerScope owner, Instant since);
    QuestionRecord findQuestion(ArchiveOwnerScope owner, String questionId, boolean lock);
    long insertQuestion(ArchiveOwnerScope owner, QuestionRecord question);
    int updateQuestion(ArchiveOwnerScope owner, QuestionRecord question,
                       long expectedVersion, long expectedSequence);

    void insertEvent(ArchiveOwnerScope owner, EventRecord event);
    List<EventRecord> listEvents(ArchiveOwnerScope owner, String questionId, long after, long through, int limit);
    Long earliestEventSequence(ArchiveOwnerScope owner, String questionId);

    void insertOutbox(ArchiveOwnerScope owner, OutboxRecord outbox);
    OutboxRecord findOutbox(ArchiveOwnerScope owner, String questionId, boolean lock);
    int updateOutbox(ArchiveOwnerScope owner, OutboxRecord outbox, long expectedFencingToken, String expectedState);
    ClaimCandidate findClaimCandidate(Instant now);
    ClaimCandidate findExhaustedCandidate(Instant now);
    List<PublishCandidate> findPublishCandidates(int limit);
    int advancePublishedSequence(ArchiveOwnerScope owner, String questionId, long expected, long delivered);

    record MutationRecord(long rowId, boolean inserted, String questionId, String requestSha256,
                          String state, Long questionRowId, Integer responseStatus,
                          String responseContentType, byte[] responseBody) { }

    record QuestionRecord(long rowId, String questionId, String editionId, String manifestSha256,
                          String blockType, String blockId, String anchorJson, String selectedText,
                          String questionText, String status, String responderId, String responderName,
                          String responderMode, String answer, int retryCount, String lastErrorCode,
                          long version, long currentSequence, Instant createdAt, Instant updatedAt,
                          Instant completedAt) { }

    record EventRecord(long rowId, String questionId, long sequence, String eventType,
                       String payloadJson, Instant occurredAt) { }

    record OutboxRecord(long rowId, String questionId, String state, int attemptCount,
                        long fencingToken, long publishedSequence, Instant availableAt,
                        Instant leaseUntil, String lastErrorCode, Instant createdAt, Instant updatedAt) { }

    record ClaimCandidate(ArchiveOwnerScope owner, String questionId) { }
    record PublishCandidate(ArchiveOwnerScope owner, String questionId,
                            long publishedSequence, long currentSequence) { }
}
