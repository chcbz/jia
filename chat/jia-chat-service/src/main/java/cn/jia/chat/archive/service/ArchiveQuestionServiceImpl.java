package cn.jia.chat.archive.service;

import cn.jia.chat.archive.dto.ArchiveQuestionDTO;
import cn.jia.chat.archive.dto.ArchiveQuestionPutRequest;
import cn.jia.chat.archive.dto.ArchiveQuestionResponderDTO;
import cn.jia.chat.archive.dto.ArchiveQuestionRetryRequest;
import cn.jia.chat.archive.dto.ArchiveTextAnchorDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.MutationRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionServiceImpl implements ArchiveQuestionService {
    static final String RESPONDER_ID = "archive-clerk-v1";
    static final String RESPONDER_NAME = "案卷书吏";
    static final String RESPONDER_MODE = "fallback";
    static final int MAX_QUESTION_BYTES = 8192;
    static final int MAX_ANSWER_BYTES = 131072;
    private static final int RATE_LIMIT_PER_MINUTE = 20;
    private static final String JSON = "application/json;charset=UTF-8";
    private static final Duration RETENTION = Duration.ofDays(7);

    private final ArchiveQuestionStore store;
    private final ArchiveTransactions transactions;
    private final ArchiveQuestionProvider provider;
    private final ArchiveQuestionEventDelivery delivery;
    private final ArchiveTextSelectionValidator selectionValidator;
    private final ArchiveWriteJson json = new ArchiveWriteJson();
    private final Clock clock;

    public ArchiveQuestionServiceImpl(ArchiveQuestionStore store, ArchivePersonalDataStore content,
                                      ArchiveTransactions transactions, ArchiveQuestionProvider provider,
                                      ArchiveQuestionEventDelivery delivery) {
        this(store, content, transactions, provider, delivery, Clock.systemUTC());
    }

    ArchiveQuestionServiceImpl(ArchiveQuestionStore store, ArchivePersonalDataStore content,
                               ArchiveTransactions transactions, ArchiveQuestionProvider provider,
                               ArchiveQuestionEventDelivery delivery, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.selectionValidator = new ArchiveTextSelectionValidator(content);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ArchiveMutationResult create(ArchiveOwnerScope owner, String questionId, String path,
                                        String key, byte[] body) {
        requireQuestionId(questionId);
        requireMutationSyntax(path, key, "/archive/v1/me/questions/" + questionId);
        ArchiveWriteJson.Parsed parsed = json.parseQuestion(body, ArchiveQuestionPutRequest.class);
        ArchiveQuestionPutRequest request = parsed.value(ArchiveQuestionPutRequest.class);
        require(request.question() != null && request.anchor() != null,
                422, "INVALID_REQUEST_JSON", "Question request is incomplete");
        int questionBytes = request.question().getBytes(StandardCharsets.UTF_8).length;
        require(!request.question().isBlank() && questionBytes <= MAX_QUESTION_BYTES,
                422, "INVALID_REQUEST_JSON", "Question must be 1..8192 UTF-8 bytes");
        boolean providerAvailable = providerAvailable();
        return mutate(owner, questionId, "PUT", path, key, parsed.canonicalJson(), () -> {
            require(providerAvailable, 503, "QUESTION_PROVIDER_UNAVAILABLE",
                    "Archive question provider is unavailable");
            require(store.countRecentQuestions(owner, clock.instant().minus(Duration.ofMinutes(1)))
                            < RATE_LIMIT_PER_MINUTE,
                    429, "QUESTION_RATE_LIMITED", "Archive question rate limit exceeded");
            ArchiveTextSelectionValidator.Selection selection = selectionValidator.reconstruct(request.anchor());
            Instant now = clock.instant();
            String anchorJson = json.canonicalValue(request.anchor());
            QuestionRecord proposed = new QuestionRecord(0, questionId, selection.editionId(),
                    selection.manifestSha256(), selection.blockType(), selection.blockId(), anchorJson,
                    selection.selectedText(), request.question(), "QUEUED", RESPONDER_ID, RESPONDER_NAME,
                    RESPONDER_MODE, "", 0, null, 1, 1, now, now, null);
            long rowId = store.insertQuestion(owner, proposed);
            if (rowId == 0) {
                QuestionRecord existing = store.findQuestion(owner, questionId, true);
                conflict(existing == null ? 0 : existing.version());
            }
            QuestionRecord created = withRowId(proposed, rowId);
            EventRecord event = event(questionId, 1, "QUESTION_QUEUED", queuedPayload(0), now);
            store.insertEvent(owner, event);
            store.insertOutbox(owner, new OutboxRecord(0, questionId, "READY", 0, 0, 0,
                    now, null, null, now, now));
            transactions.afterCommit(() -> delivery.afterCommit(owner, event));
            return created;
        });
    }

    @Override
    public ArchiveQuestionDTO get(ArchiveOwnerScope owner, String questionId) {
        requireQuestionId(questionId);
        QuestionRecord question = store.findQuestion(owner, questionId, false);
        if (question == null) notFound();
        return dto(question);
    }

    @Override
    public ArchiveMutationResult retry(ArchiveOwnerScope owner, String questionId, String path,
                                       String key, byte[] body) {
        requireQuestionId(questionId);
        requireMutationSyntax(path, key, "/archive/v1/me/questions/" + questionId + "/retry");
        ArchiveWriteJson.Parsed parsed = json.parseQuestion(body, ArchiveQuestionRetryRequest.class);
        ArchiveQuestionRetryRequest request = parsed.value(ArchiveQuestionRetryRequest.class);
        require(request.expectedVersion() != null, 422, "INVALID_REQUEST_JSON", "Retry request is incomplete");
        long expectedVersion = ArchiveWire.decimal(request.expectedVersion(), "INVALID_VERSION");
        boolean providerAvailable = providerAvailable();
        return mutate(owner, questionId, "POST", path, key, parsed.canonicalJson(), () -> {
            require(providerAvailable, 503, "QUESTION_PROVIDER_UNAVAILABLE",
                    "Archive question provider is unavailable");
            QuestionRecord current = store.findQuestion(owner, questionId, true);
            if (current == null) notFound();
            if (current.version() != expectedVersion || !"FAILED_RETRYABLE".equals(current.status())) {
                conflict(current.version());
            }
            OutboxRecord outbox = store.findOutbox(owner, questionId, true);
            require(outbox != null && "WAITING_RETRY".equals(outbox.state()) && outbox.attemptCount() < 3,
                    409, "VERSION_CONFLICT", "Question is not retryable");
            long nextVersion = nextVersion(current.version());
            long nextSequence = nextSequence(current.currentSequence());
            Instant now = clock.instant();
            QuestionRecord queued = change(current, "QUEUED", "", current.retryCount(), null,
                    nextVersion, nextSequence, now, null);
            require(store.updateQuestion(owner, queued, current.version(), current.currentSequence()) == 1,
                    409, "VERSION_CONFLICT", "Question retry CAS failed");
            OutboxRecord ready = new OutboxRecord(outbox.rowId(), questionId, "READY", outbox.attemptCount(),
                    outbox.fencingToken(), outbox.publishedSequence(), now, null, null,
                    outbox.createdAt(), now);
            require(store.updateOutbox(owner, ready, outbox.fencingToken(), "WAITING_RETRY") == 1,
                    409, "VERSION_CONFLICT", "Question retry outbox CAS failed");
            EventRecord event = event(questionId, nextSequence, "QUESTION_RETRY_QUEUED",
                    queuedPayload(current.retryCount()), now);
            store.insertEvent(owner, event);
            transactions.afterCommit(() -> delivery.afterCommit(owner, event));
            return queued;
        });
    }

    private ArchiveMutationResult mutate(ArchiveOwnerScope owner, String questionId, String method,
                                         String path, String key, byte[] canonicalJson,
                                         Supplier<QuestionRecord> action) {
        String hash = json.sha256(method, path, canonicalJson);
        return transactions.required(() -> {
            MutationRecord mutation = store.insertOrLockMutation(owner, questionId, method, path, key,
                    hash, clock.instant().plus(RETENTION));
            require(mutation != null, 409, "IDEMPOTENCY_KEY_REUSED", "Question mutation is unavailable");
            if (!hash.equals(mutation.requestSha256()) || !questionId.equals(mutation.questionId())) {
                throw new ArchivePersonalDataException(409, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was reused with a different request");
            }
            if ("COMPLETED".equals(mutation.state())) {
                return new ArchiveMutationResult(mutation.responseStatus(), mutation.responseContentType(),
                        mutation.responseBody(), true);
            }
            require(mutation.inserted(), 409, "IDEMPOTENCY_KEY_REUSED",
                    "Incomplete question mutation reservation");
            QuestionRecord result = action.get();
            byte[] response = json.success(dto(result));
            require(store.completeMutation(owner, mutation.rowId(), hash, result.rowId(), 202, JSON, response) == 1,
                    409, "IDEMPOTENCY_KEY_REUSED", "Question mutation finalization failed");
            return new ArchiveMutationResult(202, JSON, response, false);
        });
    }

    ArchiveQuestionDTO dto(QuestionRecord row) {
        ArchiveTextAnchorDTO anchor = json.readValue(row.anchorJson(), ArchiveTextAnchorDTO.class);
        return new ArchiveQuestionDTO(row.questionId(), Long.toString(row.version()),
                Long.toString(row.currentSequence()), row.status(),
                new ArchiveQuestionResponderDTO(RESPONDER_ID, RESPONDER_NAME, RESPONDER_MODE),
                row.questionText(), anchor, row.selectedText(), row.answer(), row.retryCount(),
                row.lastErrorCode(), time(row.createdAt()), time(row.updatedAt()), time(row.completedAt()));
    }

    EventRecord event(String questionId, long sequence, String type, Map<String, Object> payload, Instant now) {
        ArchiveQuestionEventCatalog.validate(type, payload);
        return new EventRecord(0, questionId, sequence, type, json.canonicalValue(payload), now);
    }

    Map<String, Object> queuedPayload(int retryCount) {
        Map<String, Object> responder = new LinkedHashMap<>();
        responder.put("id", RESPONDER_ID);
        responder.put("displayName", RESPONDER_NAME);
        responder.put("mode", RESPONDER_MODE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "QUEUED");
        payload.put("responder", responder);
        payload.put("retryCount", retryCount);
        return payload;
    }

    static QuestionRecord change(QuestionRecord row, String status, String answer, int retryCount,
                                 String error, long version, long sequence, Instant updated, Instant completed) {
        return new QuestionRecord(row.rowId(), row.questionId(), row.editionId(), row.manifestSha256(),
                row.blockType(), row.blockId(), row.anchorJson(), row.selectedText(), row.questionText(),
                status, RESPONDER_ID, RESPONDER_NAME, RESPONDER_MODE, answer, retryCount, error,
                version, sequence, row.createdAt(), updated, completed);
    }

    private QuestionRecord withRowId(QuestionRecord row, long rowId) {
        return new QuestionRecord(rowId, row.questionId(), row.editionId(), row.manifestSha256(),
                row.blockType(), row.blockId(), row.anchorJson(), row.selectedText(), row.questionText(),
                row.status(), row.responderId(), row.responderName(), row.responderMode(), row.answer(),
                row.retryCount(), row.lastErrorCode(), row.version(), row.currentSequence(), row.createdAt(),
                row.updatedAt(), row.completedAt());
    }

    private boolean providerAvailable() {
        try { return provider.available(); }
        catch (Throwable ignored) { return false; }
    }
    private void requireQuestionId(String questionId) {
        if (!ArchiveWire.lowercaseUuid(questionId)) notFound();
    }
    private void requireMutationSyntax(String path, String key, String expectedPath) {
        if (!ArchiveWire.visibleAsciiPath(path) || !expectedPath.equals(path)) notFound();
        require(ArchiveWire.visibleAsciiKey(key), 422, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must be 1..128 visible ASCII bytes");
    }
    private long nextVersion(long current) { return Long.parseLong(ArchiveWire.next(current)); }
    private long nextSequence(long current) {
        if (current == Long.MAX_VALUE) {
            throw new ArchivePersonalDataException(409, "SEQUENCE_EXHAUSTED",
                    "Question event sequence is exhausted", Long.toString(current));
        }
        return current + 1;
    }
    private void conflict(long current) {
        throw new ArchivePersonalDataException(409, "VERSION_CONFLICT",
                "Question version conflict", Long.toString(current));
    }
    private void notFound() {
        throw new ArchivePersonalDataException(404, "ARCHIVE_RESOURCE_NOT_FOUND",
                "Archive resource is not available");
    }
    private void require(boolean condition, int status, String code, String message) {
        if (!condition) throw new ArchivePersonalDataException(status, code, message);
    }
    private String time(Instant value) { return value == null ? null : value.toString(); }
}
