package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class ArchiveQuestionTestSupport {
    private ArchiveQuestionTestSupport() { }

    static final class Content implements ArchivePersonalDataStore {
        ActiveEdition active;
        final Map<String, ContentPoint> points = new LinkedHashMap<>();
        @Override public ActiveEdition lockActiveEdition(String editionId) {
            return active != null && active.editionId().equals(editionId) ? active : null;
        }
        @Override public ContentPoint lockContentPoint(String editionId, String blockId, String paragraphId) {
            ContentPoint point = points.get(paragraphId);
            return point != null && point.editionId().equals(editionId) && point.blockId().equals(blockId) ? point : null;
        }
        @Override public List<ContentPoint> lockBlockParagraphs(String editionId, String blockId, List<String> ids) {
            return ids.stream().distinct().map(points::get).filter(java.util.Objects::nonNull)
                    .filter(point -> point.editionId().equals(editionId) && point.blockId().equals(blockId))
                    .sorted(Comparator.comparingInt(ContentPoint::paragraphOrdinal)).toList();
        }
        @Override public IdempotencyRecord insertOrLockIdempotency(ArchiveOwnerScope owner, String method, String path, String key, String hash, Instant expiresAt) { throw new UnsupportedOperationException(); }
        @Override public int completeIdempotency(ArchiveOwnerScope owner, long rowId, String hash, int status, String contentType, byte[] body) { throw new UnsupportedOperationException(); }
        @Override public ProgressRecord findProgress(ArchiveOwnerScope owner, String editionId, boolean lock) { throw new UnsupportedOperationException(); }
        @Override public void insertProgress(ArchiveOwnerScope owner, ProgressRecord row) { throw new UnsupportedOperationException(); }
        @Override public int updateProgress(ArchiveOwnerScope owner, ProgressRecord row, long expectedVersion) { throw new UnsupportedOperationException(); }
        @Override public BookmarkRecord findBookmark(ArchiveOwnerScope owner, String bookmarkId, boolean lock) { throw new UnsupportedOperationException(); }
        @Override public void insertBookmark(ArchiveOwnerScope owner, BookmarkRecord row) { throw new UnsupportedOperationException(); }
        @Override public int updateBookmark(ArchiveOwnerScope owner, BookmarkRecord row, long expectedVersion) { throw new UnsupportedOperationException(); }
        @Override public List<BookmarkRecord> listBookmarks(ArchiveOwnerScope owner, String editionId, Long beforeRowId, int limit) { throw new UnsupportedOperationException(); }
        @Override public NoteRecord findNote(ArchiveOwnerScope owner, String noteId, boolean lock) { throw new UnsupportedOperationException(); }
        @Override public void insertNote(ArchiveOwnerScope owner, NoteRecord row) { throw new UnsupportedOperationException(); }
        @Override public int updateNote(ArchiveOwnerScope owner, NoteRecord row, long expectedVersion) { throw new UnsupportedOperationException(); }
        @Override public List<NoteRecord> listNotes(ArchiveOwnerScope owner, String editionId, String blockId, Long beforeRowId, int limit) { throw new UnsupportedOperationException(); }
    }

    static class Store implements ArchiveQuestionStore {
        private final Map<String, MutationRecord> mutations = new LinkedHashMap<>();
        private final Map<String, QuestionRecord> questions = new LinkedHashMap<>();
        private final Map<String, List<EventRecord>> events = new LinkedHashMap<>();
        private final Map<String, OutboxRecord> outboxes = new LinkedHashMap<>();
        private long rows;
        int mutationReservations;
        int questionInserts;
        int eventInserts;
        int outboxInserts;
        int claimCandidateQueries;
        int exhaustedCandidateQueries;
        int claimCandidatesReturned;
        int exhaustedCandidatesReturned;
        boolean failNextQuestionUpdate;
        int failQuestionUpdatesRemaining;
        boolean failNextOutboxUpdate;
        boolean failNextEventInsert;
        boolean failNextLeaseRenewal;
        boolean failNextAdvancePublishedSequence;
        int failEventInsertAt;
        final AtomicInteger leaseRenewals = new AtomicInteger();

        @Override public synchronized MutationRecord insertOrLockMutation(ArchiveOwnerScope owner, String questionId, String method,
                String path, String key, String hash, Instant expiresAt) {
            mutationReservations++;
            String compound = mutationKey(owner, method, path, key);
            MutationRecord existing = mutations.get(compound);
            if (existing != null) return new MutationRecord(existing.rowId(), false, existing.questionId(),
                    existing.requestSha256(), existing.state(), existing.questionRowId(), existing.responseStatus(),
                    existing.responseContentType(), clone(existing.responseBody()));
            MutationRecord created = new MutationRecord(++rows, true, questionId, hash, "PENDING",
                    null, null, null, null);
            mutations.put(compound, created);
            return created;
        }
        @Override public synchronized int completeMutation(ArchiveOwnerScope owner, long rowId, String hash, long questionRowId,
                int status, String contentType, byte[] body) {
            for (var entry : mutations.entrySet()) {
                MutationRecord row = entry.getValue();
                if (row.rowId() == rowId && row.requestSha256().equals(hash) && "PENDING".equals(row.state())) {
                    entry.setValue(new MutationRecord(rowId, false, row.questionId(), hash, "COMPLETED",
                            questionRowId, status, contentType, clone(body)));
                    return 1;
                }
            }
            return 0;
        }
        @Override public synchronized long countRecentQuestions(ArchiveOwnerScope owner, Instant since) {
            return questions.entrySet().stream().filter(entry -> entry.getKey().startsWith(scope(owner) + "\n"))
                    .map(Map.Entry::getValue).filter(row -> !row.createdAt().isBefore(since)).count();
        }
        @Override public synchronized QuestionRecord findQuestion(ArchiveOwnerScope owner, String questionId, boolean lock) {
            return questions.get(questionKey(owner, questionId));
        }
        @Override public synchronized long insertQuestion(ArchiveOwnerScope owner, QuestionRecord question) {
            String key = questionKey(owner, question.questionId());
            if (questions.containsKey(key)) return 0;
            long rowId = ++rows;
            questions.put(key, withRowId(question, rowId));
            questionInserts++;
            return rowId;
        }
        @Override public synchronized int updateQuestion(ArchiveOwnerScope owner, QuestionRecord row, long expectedVersion, long expectedSequence) {
            if (failNextQuestionUpdate) {
                failNextQuestionUpdate = false;
                throw new IllegalStateException("injected question persistence failure");
            }
            if (failQuestionUpdatesRemaining > 0) {
                failQuestionUpdatesRemaining--;
                throw new IllegalStateException("injected repeated question persistence failure");
            }
            String key = questionKey(owner, row.questionId());
            QuestionRecord current = questions.get(key);
            if (current == null || current.version() != expectedVersion || current.currentSequence() != expectedSequence) return 0;
            questions.put(key, row);
            return 1;
        }
        @Override public synchronized void insertEvent(ArchiveOwnerScope owner, EventRecord event) {
            if (failNextEventInsert) { failNextEventInsert = false; throw new IllegalStateException("injected event persistence failure"); }
            if (failEventInsertAt > 0 && eventInserts + 1 == failEventInsertAt) {
                failEventInsertAt = 0;
                throw new IllegalStateException("injected later event persistence failure");
            }
            String key = questionKey(owner, event.questionId());
            List<EventRecord> rowsForQuestion = events.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (rowsForQuestion.stream().anyMatch(row -> row.sequence() == event.sequence())) throw new IllegalStateException("duplicate sequence");
            rowsForQuestion.add(new EventRecord(++rows, event.questionId(), event.sequence(), event.eventType(), event.payloadJson(), event.occurredAt()));
            rowsForQuestion.sort(Comparator.comparingLong(EventRecord::sequence));
            eventInserts++;
        }
        @Override public synchronized List<EventRecord> listEvents(ArchiveOwnerScope owner, String questionId, long after, long through, int limit) {
            return events.getOrDefault(questionKey(owner, questionId), List.of()).stream()
                    .filter(row -> row.sequence() > after && row.sequence() <= through)
                    .sorted(Comparator.comparingLong(EventRecord::sequence)).limit(limit).toList();
        }
        @Override public synchronized Long earliestEventSequence(ArchiveOwnerScope owner, String questionId) {
            var minimum = events.getOrDefault(questionKey(owner, questionId), List.of()).stream()
                    .mapToLong(EventRecord::sequence).min();
            return minimum.isPresent() ? minimum.getAsLong() : null;
        }
        @Override public synchronized void insertOutbox(ArchiveOwnerScope owner, OutboxRecord row) {
            String key = questionKey(owner, row.questionId());
            if (outboxes.containsKey(key)) throw new IllegalStateException("duplicate outbox");
            outboxes.put(key, withRowId(row, ++rows));
            outboxInserts++;
        }
        @Override public synchronized OutboxRecord findOutbox(ArchiveOwnerScope owner, String questionId, boolean lock) {
            return outboxes.get(questionKey(owner, questionId));
        }
        @Override public synchronized int updateOutbox(ArchiveOwnerScope owner, OutboxRecord row, long expectedToken, String expectedState) {
            if (failNextOutboxUpdate) { failNextOutboxUpdate = false; throw new IllegalStateException("injected outbox persistence failure"); }
            String key = questionKey(owner, row.questionId());
            OutboxRecord current = outboxes.get(key);
            if (current == null || current.fencingToken() != expectedToken || !current.state().equals(expectedState)) return 0;
            outboxes.put(key, row);
            return 1;
        }
        @Override public synchronized int renewOutboxLease(ArchiveOwnerScope owner, String questionId, long token,
                                               Instant leaseUntil, Instant updatedAt) {
            leaseRenewals.incrementAndGet();
            if (failNextLeaseRenewal) { failNextLeaseRenewal = false; return 0; }
            String key = questionKey(owner, questionId);
            OutboxRecord row = outboxes.get(key);
            if (row == null || !"LEASED".equals(row.state()) || row.fencingToken() != token
                    || row.leaseUntil() == null || !row.leaseUntil().isAfter(updatedAt)) return 0;
            outboxes.put(key, new OutboxRecord(row.rowId(), row.questionId(), row.state(), row.attemptCount(),
                    row.fencingToken(), row.publishedSequence(), row.availableAt(), leaseUntil, row.lastErrorCode(),
                    row.createdAt(), updatedAt));
            return 1;
        }
        @Override public synchronized List<ClaimCandidate> listClaimCandidates(Instant now, Instant afterCandidateAt,
                                                                   long afterRowId, int limit) {
            claimCandidateQueries++;
            List<ClaimCandidate> result = outboxes.entrySet().stream().filter(entry -> {
                OutboxRecord row = entry.getValue();
                return row.attemptCount() < 3 && (("READY".equals(row.state())
                        && !row.availableAt().isAfter(now)) || ("LEASED".equals(row.state())
                        && row.leaseUntil() != null && !row.leaseUntil().isAfter(now)));
            }).map(entry -> candidate(entry.getKey(), claimOrder(entry.getValue())))
                    .filter(candidate -> after(candidate, afterCandidateAt, afterRowId))
                    .sorted(Comparator.comparing(ClaimCandidate::candidateAt)
                            .thenComparingLong(ClaimCandidate::rowId))
                    .limit(limit).toList();
            claimCandidatesReturned += result.size();
            return result;
        }
        @Override public synchronized List<ClaimCandidate> listExhaustedCandidates(Instant now, Instant afterLeaseUntil,
                                                                      long afterRowId, int limit) {
            exhaustedCandidateQueries++;
            List<ClaimCandidate> result = outboxes.entrySet().stream().filter(entry -> {
                OutboxRecord row = entry.getValue();
                return "LEASED".equals(row.state()) && row.attemptCount() >= 3
                        && row.leaseUntil() != null && !row.leaseUntil().isAfter(now);
            }).map(entry -> candidate(entry.getKey(), entry.getValue().leaseUntil()))
                    .filter(candidate -> after(candidate, afterLeaseUntil, afterRowId))
                    .sorted(Comparator.comparing(ClaimCandidate::candidateAt)
                            .thenComparingLong(ClaimCandidate::rowId))
                    .limit(limit).toList();
            exhaustedCandidatesReturned += result.size();
            return result;
        }
        @Override public synchronized List<PublishCandidate> findPublishCandidates(long afterRowId, int limit) {
            return outboxes.entrySet().stream().filter(entry -> entry.getValue().rowId() > afterRowId)
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().rowId())).map(entry -> {
                QuestionRecord question = questions.get(entry.getKey());
                OutboxRecord outbox = entry.getValue();
                if (question == null || outbox.publishedSequence() >= question.currentSequence()) return null;
                ClaimCandidate parsed = candidate(entry.getKey(), outbox.availableAt());
                return new PublishCandidate(parsed.owner(), parsed.questionId(), outbox.rowId(),
                        outbox.publishedSequence(), question.currentSequence());
            }).filter(java.util.Objects::nonNull).limit(limit).toList();
        }
        @Override public synchronized int advancePublishedSequence(ArchiveOwnerScope owner, String questionId, long expected, long delivered) {
            if (failNextAdvancePublishedSequence) {
                failNextAdvancePublishedSequence = false;
                throw new IllegalStateException("injected watermark persistence failure");
            }
            String key = questionKey(owner, questionId);
            OutboxRecord row = outboxes.get(key);
            if (row == null || row.publishedSequence() != expected) return 0;
            outboxes.put(key, new OutboxRecord(row.rowId(), row.questionId(), row.state(), row.attemptCount(),
                    row.fencingToken(), delivered, row.availableAt(), row.leaseUntil(), row.lastErrorCode(),
                    row.createdAt(), row.updatedAt()));
            return 1;
        }

        synchronized void deleteEvent(ArchiveOwnerScope owner, String questionId, long sequence) {
            List<EventRecord> rowsForQuestion = events.get(questionKey(owner, questionId));
            if (rowsForQuestion != null) rowsForQuestion.removeIf(row -> row.sequence() == sequence);
        }
        synchronized void setQuestion(ArchiveOwnerScope owner, QuestionRecord row) {
            questions.put(questionKey(owner, row.questionId()), row);
        }
        synchronized void setOutbox(ArchiveOwnerScope owner, OutboxRecord row) {
            String key = questionKey(owner, row.questionId());
            boolean duplicateRowId = outboxes.entrySet().stream()
                    .anyMatch(entry -> !entry.getKey().equals(key) && entry.getValue().rowId() == row.rowId());
            if (row.rowId() <= 0 || duplicateRowId) {
                outboxes.put(key, withRowId(row, ++rows));
            } else {
                rows = Math.max(rows, row.rowId());
                outboxes.put(key, row);
            }
        }
        synchronized void removeQuestionAndOutbox(ArchiveOwnerScope owner, String questionId) {
            questions.remove(questionKey(owner, questionId));
            outboxes.remove(questionKey(owner, questionId));
        }
        synchronized List<EventRecord> allEvents(ArchiveOwnerScope owner, String id) { return List.copyOf(events.getOrDefault(questionKey(owner, id), List.of())); }

        synchronized Snapshot snapshot() {
            return new Snapshot(new LinkedHashMap<>(mutations), new LinkedHashMap<>(questions), copyEvents(events),
                    new LinkedHashMap<>(outboxes), rows, mutationReservations, questionInserts, eventInserts, outboxInserts);
        }
        synchronized void restore(Snapshot snapshot) {
            mutations.clear(); mutations.putAll(snapshot.mutations());
            questions.clear(); questions.putAll(snapshot.questions());
            events.clear(); events.putAll(copyEvents(snapshot.events()));
            outboxes.clear(); outboxes.putAll(snapshot.outboxes());
            rows = snapshot.rows(); mutationReservations = snapshot.mutationReservations();
            questionInserts = snapshot.questionInserts(); eventInserts = snapshot.eventInserts(); outboxInserts = snapshot.outboxInserts();
        }
        private static Map<String, List<EventRecord>> copyEvents(Map<String, List<EventRecord>> source) {
            Map<String, List<EventRecord>> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
            return copy;
        }
        private QuestionRecord withRowId(QuestionRecord row, long id) { return new QuestionRecord(id, row.questionId(), row.editionId(), row.manifestSha256(), row.blockType(), row.blockId(), row.anchorJson(), row.selectedText(), row.questionText(), row.status(), row.responderId(), row.responderName(), row.responderMode(), row.answer(), row.retryCount(), row.lastErrorCode(), row.version(), row.currentSequence(), row.createdAt(), row.updatedAt(), row.completedAt()); }
        private OutboxRecord withRowId(OutboxRecord row, long id) { return new OutboxRecord(id, row.questionId(), row.state(), row.attemptCount(), row.fencingToken(), row.publishedSequence(), row.availableAt(), row.leaseUntil(), row.lastErrorCode(), row.createdAt(), row.updatedAt()); }
        private ClaimCandidate candidate(String key, Instant candidateAt) {
            String[] parts = key.split("\\n", 4);
            OutboxRecord outbox = outboxes.get(key);
            return new ClaimCandidate(new ArchiveOwnerScope(parts[0], parts[1], parts[2]), parts[3],
                    candidateAt, outbox.rowId());
        }
        private Instant claimOrder(OutboxRecord row) {
            return "READY".equals(row.state()) ? row.availableAt() : row.leaseUntil();
        }
        private boolean after(ClaimCandidate candidate, Instant cursorAt, long cursorRowId) {
            if (cursorAt == null) return true;
            int timeOrder = candidate.candidateAt().compareTo(cursorAt);
            return timeOrder > 0 || (timeOrder == 0 && candidate.rowId() > cursorRowId);
        }
        private String mutationKey(ArchiveOwnerScope owner, String method, String path, String key) { return scope(owner) + "\n" + method + "\n" + path + "\n" + key; }
        private String questionKey(ArchiveOwnerScope owner, String id) { return scope(owner) + "\n" + id; }
        private String scope(ArchiveOwnerScope owner) { return owner.tenantId() + "\n" + owner.clientId() + "\n" + owner.ownerJiacn(); }
        private byte[] clone(byte[] value) { return value == null ? null : value.clone(); }

        record Snapshot(Map<String, MutationRecord> mutations, Map<String, QuestionRecord> questions,
                        Map<String, List<EventRecord>> events, Map<String, OutboxRecord> outboxes,
                        long rows, int mutationReservations, int questionInserts, int eventInserts, int outboxInserts) { }
    }

    static final class Transactions implements ArchiveTransactions {
        private final Store store;
        private final ThreadLocal<List<Runnable>> callbacks = new ThreadLocal<>();
        boolean failAfterCommitOnce;
        Transactions(Store store) { this.store = store; }
        @Override public <T> T required(Supplier<T> action) {
            return run(action, true);
        }
        @Override public <T> T requiresNew(Supplier<T> action) {
            return run(action, false);
        }
        private synchronized <T> T run(Supplier<T> action, boolean injectResponseLoss) {
            Store.Snapshot snapshot = store.snapshot();
            List<Runnable> prior = callbacks.get();
            List<Runnable> current = new ArrayList<>();
            callbacks.set(current);
            T result;
            try { result = action.get(); }
            catch (Throwable failure) {
                store.restore(snapshot);
                callbacks.set(prior);
                throw failure;
            }
            callbacks.set(prior);
            for (Runnable callback : current) callback.run();
            if (injectResponseLoss && failAfterCommitOnce) {
                failAfterCommitOnce = false;
                throw new IllegalStateException("injected response loss after commit");
            }
            return result;
        }
        @Override public void afterCommit(Runnable action) {
            List<Runnable> current = callbacks.get();
            if (current == null) throw new IllegalStateException("afterCommit outside transaction");
            current.add(action);
        }
    }
}
