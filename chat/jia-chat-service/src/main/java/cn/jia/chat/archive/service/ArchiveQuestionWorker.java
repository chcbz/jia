package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.ClaimCandidate;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionWorker {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final int MAX_DELTA_UTF8_BYTES = 4096;
    private final ArchiveQuestionStore store;
    private final ArchiveTransactions transactions;
    private final ArchiveQuestionProvider provider;
    private final ArchiveQuestionEventDelivery delivery;
    private final ArchiveQuestionAccessPolicy accessPolicy;
    private final ArchiveWriteJson json = new ArchiveWriteJson();
    private final Clock clock;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public ArchiveQuestionWorker(ArchiveQuestionStore store, ArchiveTransactions transactions,
                                 ArchiveQuestionProvider provider, ArchiveQuestionEventDelivery delivery,
                                 ArchiveQuestionAccessPolicy accessPolicy) {
        this(store, transactions, provider, delivery, accessPolicy, Clock.systemUTC());
    }

    ArchiveQuestionWorker(ArchiveQuestionStore store, ArchiveTransactions transactions,
                          ArchiveQuestionProvider provider, ArchiveQuestionEventDelivery delivery,
                          ArchiveQuestionAccessPolicy accessPolicy, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start() {
        if (!accessPolicy.enabled() || !started.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "archive-question-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safeRun, 0, 250, TimeUnit.MILLISECONDS);
    }

    public boolean runOnce() {
        if (!accessPolicy.enabled()) return false;
        delivery.recoverOnce();
        ClaimCandidate exhausted = store.findExhaustedCandidate(clock.instant());
        if (exhausted != null) {
            if (!allowed(exhausted.owner())) return false;
            finalizeExpired(exhausted);
            return true;
        }
        ClaimCandidate candidate = store.findClaimCandidate(clock.instant());
        if (candidate == null || !allowed(candidate.owner())) return false;
        Claimed claim = claim(candidate);
        if (claim == null) return false;
        executeProvider(claim);
        return true;
    }

    private void safeRun() {
        try {
            if (!accessPolicy.enabled()) { stop(); return; }
            runOnce();
        } catch (Throwable ignored) {
            // Durable outbox/recovery retries on the next poll. Never fail application threads.
        }
    }

    private Claimed claim(ClaimCandidate candidate) {
        return transactions.required(() -> {
            QuestionRecord question = store.findQuestion(candidate.owner(), candidate.questionId(), true);
            if (question == null) return null;
            OutboxRecord outbox = store.findOutbox(candidate.owner(), candidate.questionId(), true);
            Instant now = clock.instant();
            boolean claimable = outbox != null && outbox.attemptCount() < 3
                    && !outbox.availableAt().isAfter(now)
                    && ("READY".equals(outbox.state()) || ("LEASED".equals(outbox.state())
                    && outbox.leaseUntil() != null && !outbox.leaseUntil().isAfter(now)));
            if (!claimable || !("QUEUED".equals(question.status()) || "RUNNING".equals(question.status()))) {
                return null;
            }
            long token = nextFence(outbox.fencingToken());
            int attempt = outbox.attemptCount() + 1;
            long version = nextVersion(question.version());
            long sequence = nextSequence(question.currentSequence());
            QuestionRecord running = ArchiveQuestionServiceImpl.change(question, "RUNNING", "", attempt - 1,
                    null, version, sequence, now, null);
            require(store.updateQuestion(candidate.owner(), running, question.version(),
                    question.currentSequence()) == 1, "Question claim CAS failed");
            OutboxRecord leased = new OutboxRecord(outbox.rowId(), question.questionId(), "LEASED", attempt,
                    token, outbox.publishedSequence(), now, now.plus(LEASE), null,
                    outbox.createdAt(), now);
            require(store.updateOutbox(candidate.owner(), leased, outbox.fencingToken(), outbox.state()) == 1,
                    "Outbox claim CAS failed");
            EventRecord event = event(question.questionId(), sequence, "QUESTION_RUNNING",
                    Map.of("status", "RUNNING", "attempt", attempt, "retryCount", attempt - 1), now);
            store.insertEvent(candidate.owner(), event);
            transactions.afterCommit(() -> delivery.afterCommit(candidate.owner(), event));
            return new Claimed(candidate.owner(), question.questionId(), token, attempt,
                    question.questionText(), question.selectedText());
        });
    }

    private void executeProvider(Claimed claim) {
        try {
            if (!provider.available()) {
                fail(claim, "QUESTION_PROVIDER_UNAVAILABLE", true);
                return;
            }
            ArchiveQuestionProvider.Answer answer = provider.answer(new ArchiveQuestionProvider.Request(
                    claim.questionId(), claim.question(), claim.selectedText()));
            if (answer == null) {
                fail(claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
                return;
            }
            int totalBytes = 0;
            StringBuilder completeAnswer = new StringBuilder();
            for (String delta : answer.deltas()) {
                if (!validUnicode(delta)) {
                    fail(claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
                    return;
                }
                totalBytes = Math.addExact(totalBytes, delta.getBytes(StandardCharsets.UTF_8).length);
                if (totalBytes > ArchiveQuestionServiceImpl.MAX_ANSWER_BYTES) {
                    fail(claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
                    return;
                }
                completeAnswer.append(delta);
            }
            for (String chunk : chunks(completeAnswer.toString())) if (!persistDelta(claim, chunk)) return;
            complete(claim);
        } catch (ArchiveQuestionProviderException failure) {
            fail(claim, safeCode(failure.code()), failure.retryable());
        } catch (IllegalArgumentException | NullPointerException invalidResponse) {
            fail(claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
        } catch (Throwable failure) {
            fail(claim, "QUESTION_PROVIDER_UNAVAILABLE", true);
        }
    }

    boolean persistDelta(Claimed claim, String delta) {
        return transactions.required(() -> {
            QuestionRecord question = store.findQuestion(claim.owner(), claim.questionId(), true);
            if (question == null) return false;
            OutboxRecord outbox = store.findOutbox(claim.owner(), claim.questionId(), true);
            if (!currentLease(outbox, claim.fencingToken()) || !"RUNNING".equals(question.status())) return false;
            String answer = question.answer() + delta;
            require(answer.getBytes(StandardCharsets.UTF_8).length <= ArchiveQuestionServiceImpl.MAX_ANSWER_BYTES,
                    "Persisted answer exceeds limit");
            Instant now = clock.instant();
            long version = nextVersion(question.version());
            long sequence = nextSequence(question.currentSequence());
            QuestionRecord updated = ArchiveQuestionServiceImpl.change(question, "RUNNING", answer,
                    question.retryCount(), null, version, sequence, now, null);
            require(store.updateQuestion(claim.owner(), updated, question.version(), question.currentSequence()) == 1,
                    "Question delta CAS failed");
            EventRecord event = event(question.questionId(), sequence, "ANSWER_DELTA", Map.of("delta", delta), now);
            store.insertEvent(claim.owner(), event);
            transactions.afterCommit(() -> delivery.afterCommit(claim.owner(), event));
            return true;
        });
    }

    boolean complete(Claimed claim) {
        return transactions.required(() -> {
            QuestionRecord question = store.findQuestion(claim.owner(), claim.questionId(), true);
            if (question == null) return false;
            OutboxRecord outbox = store.findOutbox(claim.owner(), claim.questionId(), true);
            if (!currentLease(outbox, claim.fencingToken()) || !"RUNNING".equals(question.status())) return false;
            Instant now = clock.instant();
            long version = nextVersion(question.version());
            long sequence = nextSequence(question.currentSequence());
            QuestionRecord succeeded = ArchiveQuestionServiceImpl.change(question, "SUCCEEDED", question.answer(),
                    question.retryCount(), null, version, sequence, now, now);
            require(store.updateQuestion(claim.owner(), succeeded, question.version(), question.currentSequence()) == 1,
                    "Question completion CAS failed");
            OutboxRecord done = new OutboxRecord(outbox.rowId(), question.questionId(), "DONE",
                    outbox.attemptCount(), outbox.fencingToken(), outbox.publishedSequence(), now,
                    null, null, outbox.createdAt(), now);
            require(store.updateOutbox(claim.owner(), done, claim.fencingToken(), "LEASED") == 1,
                    "Outbox completion fencing failed");
            EventRecord event = event(question.questionId(), sequence, "QUESTION_SUCCEEDED",
                    Map.of("status", "SUCCEEDED"), now);
            store.insertEvent(claim.owner(), event);
            transactions.afterCommit(() -> delivery.afterCommit(claim.owner(), event));
            return true;
        });
    }

    boolean fail(Claimed claim, String errorCode, boolean retryable) {
        try {
            return transactions.required(() -> {
                QuestionRecord question = store.findQuestion(claim.owner(), claim.questionId(), true);
                if (question == null) return false;
                OutboxRecord outbox = store.findOutbox(claim.owner(), claim.questionId(), true);
                if (!currentLease(outbox, claim.fencingToken()) || !"RUNNING".equals(question.status())) return false;
                boolean terminal = !retryable || outbox.attemptCount() >= 3;
                String status = terminal ? "FAILED_FINAL" : "FAILED_RETRYABLE";
                String eventType = terminal ? "QUESTION_FAILED_FINAL" : "QUESTION_FAILED_RETRYABLE";
                Instant now = clock.instant();
                long version = nextVersion(question.version());
                long sequence = nextSequence(question.currentSequence());
                QuestionRecord failed = ArchiveQuestionServiceImpl.change(question, status, question.answer(),
                        question.retryCount(), errorCode, version, sequence, now, terminal ? now : null);
                require(store.updateQuestion(claim.owner(), failed, question.version(), question.currentSequence()) == 1,
                        "Question failure CAS failed");
                OutboxRecord failedOutbox = new OutboxRecord(outbox.rowId(), question.questionId(),
                        terminal ? "DONE" : "WAITING_RETRY", outbox.attemptCount(), outbox.fencingToken(),
                        outbox.publishedSequence(), now, null, errorCode, outbox.createdAt(), now);
                require(store.updateOutbox(claim.owner(), failedOutbox, claim.fencingToken(), "LEASED") == 1,
                        "Outbox failure fencing failed");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", status);
                payload.put("retryCount", question.retryCount());
                payload.put("lastErrorCode", errorCode);
                EventRecord event = event(question.questionId(), sequence, eventType, payload, now);
                store.insertEvent(claim.owner(), event);
                transactions.afterCommit(() -> delivery.afterCommit(claim.owner(), event));
                return true;
            });
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void finalizeExpired(ClaimCandidate candidate) {
        transactions.required(() -> {
            QuestionRecord question = store.findQuestion(candidate.owner(), candidate.questionId(), true);
            if (question == null) return null;
            OutboxRecord outbox = store.findOutbox(candidate.owner(), candidate.questionId(), true);
            Instant now = clock.instant();
            if (outbox == null || !"LEASED".equals(outbox.state()) || outbox.attemptCount() < 3
                    || outbox.leaseUntil() == null || outbox.leaseUntil().isAfter(now)) return null;
            Claimed claim = new Claimed(candidate.owner(), candidate.questionId(), outbox.fencingToken(),
                    outbox.attemptCount(), question.questionText(), question.selectedText());
            failInsideTransaction(claim, question, outbox, "QUESTION_PROVIDER_UNAVAILABLE", now);
            return null;
        });
    }

    private void failInsideTransaction(Claimed claim, QuestionRecord question, OutboxRecord outbox,
                                       String code, Instant now) {
        long version = nextVersion(question.version());
        long sequence = nextSequence(question.currentSequence());
        QuestionRecord failed = ArchiveQuestionServiceImpl.change(question, "FAILED_FINAL", question.answer(),
                question.retryCount(), code, version, sequence, now, now);
        require(store.updateQuestion(claim.owner(), failed, question.version(), question.currentSequence()) == 1,
                "Expired question finalization CAS failed");
        OutboxRecord done = new OutboxRecord(outbox.rowId(), question.questionId(), "DONE", outbox.attemptCount(),
                outbox.fencingToken(), outbox.publishedSequence(), now, null, code, outbox.createdAt(), now);
        require(store.updateOutbox(claim.owner(), done, outbox.fencingToken(), "LEASED") == 1,
                "Expired outbox finalization CAS failed");
        EventRecord event = event(question.questionId(), sequence, "QUESTION_FAILED_FINAL",
                Map.of("status", "FAILED_FINAL", "retryCount", question.retryCount(), "lastErrorCode", code), now);
        store.insertEvent(claim.owner(), event);
        transactions.afterCommit(() -> delivery.afterCommit(claim.owner(), event));
    }


    private boolean validUnicode(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }

    private java.util.List<String> chunks(String value) {
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int chars = Character.charCount(codePoint);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 0 && bytes + codePointBytes > MAX_DELTA_UTF8_BYTES) {
                result.add(chunk.toString());
                chunk.setLength(0);
                bytes = 0;
            }
            chunk.appendCodePoint(codePoint);
            bytes += codePointBytes;
            offset += chars;
        }
        if (!chunk.isEmpty()) result.add(chunk.toString());
        return result;
    }
    private boolean allowed(ArchiveOwnerScope owner) {
        return owner != null && owner.ownerJiacn().equals(owner.tenantId())
                && accessPolicy.allows(owner.tenantId(), owner.clientId());
    }

    private boolean currentLease(OutboxRecord outbox, long token) {
        return outbox != null && "LEASED".equals(outbox.state()) && outbox.fencingToken() == token
                && outbox.leaseUntil() != null && outbox.leaseUntil().isAfter(clock.instant());
    }

    private EventRecord event(String questionId, long sequence, String type,
                              Map<String, Object> payload, Instant now) {
        ArchiveQuestionEventCatalog.validate(type, payload);
        return new EventRecord(0, questionId, sequence, type, json.canonicalValue(payload), now);
    }
    private String safeCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}")
                ? value : "QUESTION_PROVIDER_UNAVAILABLE";
    }
    private long nextVersion(long current) { return Long.parseLong(ArchiveWire.next(current)); }
    private long nextSequence(long current) {
        if (current == Long.MAX_VALUE) {
            throw new ArchivePersonalDataException(409, "SEQUENCE_EXHAUSTED",
                    "Question event sequence is exhausted", Long.toString(current));
        }
        return current + 1;
    }
    private long nextFence(long current) {
        if (current == Long.MAX_VALUE) {
            throw new ArchivePersonalDataException(409, "FENCING_TOKEN_EXHAUSTED",
                    "Question fencing token is exhausted", Long.toString(current));
        }
        return current + 1;
    }
    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @PreDestroy
    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) current.shutdownNow();
    }

    record Claimed(ArchiveOwnerScope owner, String questionId, long fencingToken, int attempt,
                   String question, String selectedText) { }
}
