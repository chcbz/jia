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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionWorker {
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofSeconds(10);
    static final int CANDIDATE_BATCH = 32;
    static final int SWEEP_INTERVAL_PAGES = 4;
    static final int RETRY_CAPACITY = 64;
    private static final int MAX_DELTA_UTF8_BYTES = 4096;
    private final ArchiveQuestionStore store;
    private final ArchiveTransactions transactions;
    private final ArchiveQuestionProvider provider;
    private final ArchiveQuestionEventDelivery delivery;
    private final ArchiveQuestionAccessPolicy accessPolicy;
    private final ArchiveWriteJson json = new ArchiveWriteJson();
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration renewInterval;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object executorLock = new Object();
    private ScheduledExecutorService executor;
    private ScheduledExecutorService leaseExecutor;
    private final RetryBudget retryBudget = new RetryBudget(RETRY_CAPACITY);
    private final QueueScanState claimScan = new QueueScanState(retryBudget);
    private final QueueScanState exhaustedScan = new QueueScanState(retryBudget);
    private boolean exhaustedFirst = true;

    public ArchiveQuestionWorker(ArchiveQuestionStore store, ArchiveTransactions transactions,
                                 ArchiveQuestionProvider provider, ArchiveQuestionEventDelivery delivery,
                                 ArchiveQuestionAccessPolicy accessPolicy) {
        this(store, transactions, provider, delivery, accessPolicy, Clock.systemUTC());
    }

    ArchiveQuestionWorker(ArchiveQuestionStore store, ArchiveTransactions transactions,
                          ArchiveQuestionProvider provider, ArchiveQuestionEventDelivery delivery,
                          ArchiveQuestionAccessPolicy accessPolicy, Clock clock) {
        this(store, transactions, provider, delivery, accessPolicy, clock,
                DEFAULT_LEASE, DEFAULT_RENEW_INTERVAL);
    }

    ArchiveQuestionWorker(ArchiveQuestionStore store, ArchiveTransactions transactions,
                          ArchiveQuestionProvider provider, ArchiveQuestionEventDelivery delivery,
                          ArchiveQuestionAccessPolicy accessPolicy, Clock clock,
                          Duration leaseDuration, Duration renewInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.renewInterval = positive(renewInterval, "renewInterval");
        if (renewInterval.compareTo(leaseDuration) >= 0 || renewInterval.toMillis() < 1) {
            throw new IllegalArgumentException("renewInterval must be at least 1ms and shorter than leaseDuration");
        }
    }

    public void start() {
        if (!accessPolicy.enabled() || !started.compareAndSet(false, true)) return;
        synchronized (executorLock) {
            executor = daemonScheduler("archive-question-worker");
            executor.scheduleWithFixedDelay(this::safeRun, 0, 250, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized boolean runOnce() {
        if (!accessPolicy.enabled()) return false;
        delivery.recoverOnce();
        Instant now = clock.instant();
        boolean expiredFirstThisRun = exhaustedFirst;
        exhaustedFirst = !exhaustedFirst;
        if (expiredFirstThisRun) {
            if (scanExpiredPage(now)) return true;
            return scanClaimPage(now);
        }
        if (scanClaimPage(now)) return true;
        return scanExpiredPage(now);
    }

    /** One exact retry plus at most one bounded keyset page is inspected for this queue/invocation. */
    private boolean scanExpiredPage(Instant now) {
        RetryAttempt retry = exhaustedScan.pollRetry();
        RetryKey attempted = retry == null ? null : RetryKey.of(retry.candidate());
        if (retry != null) {
            try {
                if (finalizeExpired(retry.candidate())) {
                    exhaustedScan.retryResolved(retry);
                    return true;
                }
                exhaustedScan.retryResolved(retry);
            } catch (Throwable failure) {
                if (permanentCandidateFailure(failure)) exhaustedScan.retryResolved(retry);
                else exhaustedScan.retryFailed(retry);
            }
        }

        ScanPlan plan = exhaustedScan.begin();
        List<ClaimCandidate> candidates;
        try {
            candidates = store.listExhaustedCandidates(now, plan.cursor().candidateAt(),
                    plan.cursor().rowId(), CANDIDATE_BATCH);
        } catch (Throwable unavailable) {
            return false;
        }
        if (candidates.isEmpty()) {
            exhaustedScan.finish(plan, true);
            return false;
        }
        boolean advanced = false;
        boolean blockedBoundaryReached = false;
        int inspected = 0;
        ScanCursor current = plan.cursor();
        for (ClaimCandidate candidate : candidates) {
            if (inspected++ >= CANDIDATE_BATCH) break;
            if (!afterCursor(candidate, current.candidateAt(), current.rowId())) continue;
            ScanCursor predecessor = current;
            ScanCursor candidateCursor = new ScanCursor(candidate.candidateAt(), candidate.rowId());
            if (plan.kind() == ScanKind.BLOCKED
                    && compareCursor(candidateCursor, plan.through()) > 0) {
                blockedBoundaryReached = true;
                break;
            }
            if (!allowed(candidate.owner()) || RetryKey.of(candidate).equals(attempted)) {
                current = candidateCursor;
                exhaustedScan.advance(plan, current);
                advanced = true;
                continue;
            }
            try {
                if (finalizeExpired(candidate)) {
                    exhaustedScan.advance(plan, candidateCursor);
                    exhaustedScan.processed(plan);
                    return true;
                }
            } catch (Throwable failure) {
                if (!permanentCandidateFailure(failure)
                        && !exhaustedScan.registerRetry(new RetryAttempt(candidate))) {
                    exhaustedScan.rememberBlocked(predecessor, candidateCursor);
                    if (plan.kind() == ScanKind.BLOCKED) exhaustedScan.blockedFailureObserved();
                }
            }
            current = candidateCursor;
            exhaustedScan.advance(plan, current);
            advanced = true;
        }
        exhaustedScan.finish(plan, !advanced || candidates.size() < CANDIDATE_BATCH
                || blockedBoundaryReached || (plan.kind() == ScanKind.BLOCKED
                && compareCursor(current, plan.through()) >= 0));
        return false;
    }

    /** One exact retry plus at most one bounded keyset page is inspected for this queue/invocation. */
    private boolean scanClaimPage(Instant now) {
        RetryAttempt retry = claimScan.pollRetry();
        RetryKey attempted = retry == null ? null : RetryKey.of(retry.candidate());
        if (retry != null) {
            try {
                Claimed claim = claim(retry.candidate());
                if (claim == null) {
                    claimScan.retryResolved(retry);
                } else {
                    executeProvider(claim);
                    claimScan.retryResolved(retry);
                    claimScan.processedRetry();
                    return true;
                }
            } catch (Throwable failure) {
                if (permanentCandidateFailure(failure)) claimScan.retryResolved(retry);
                else claimScan.retryFailed(retry);
            }
        }

        ScanPlan plan = claimScan.begin();
        List<ClaimCandidate> candidates;
        try {
            candidates = store.listClaimCandidates(now, plan.cursor().candidateAt(),
                    plan.cursor().rowId(), CANDIDATE_BATCH);
        } catch (Throwable unavailable) {
            return false;
        }
        if (candidates.isEmpty()) {
            claimScan.finish(plan, true);
            return false;
        }
        boolean advanced = false;
        boolean blockedBoundaryReached = false;
        int inspected = 0;
        ScanCursor current = plan.cursor();
        for (ClaimCandidate candidate : candidates) {
            if (inspected++ >= CANDIDATE_BATCH) break;
            if (!afterCursor(candidate, current.candidateAt(), current.rowId())) continue;
            ScanCursor predecessor = current;
            ScanCursor candidateCursor = new ScanCursor(candidate.candidateAt(), candidate.rowId());
            if (plan.kind() == ScanKind.BLOCKED
                    && compareCursor(candidateCursor, plan.through()) > 0) {
                blockedBoundaryReached = true;
                break;
            }
            if (!allowed(candidate.owner()) || RetryKey.of(candidate).equals(attempted)) {
                current = candidateCursor;
                claimScan.advance(plan, current);
                advanced = true;
                continue;
            }
            try {
                Claimed claim = claim(candidate);
                if (claim != null) {
                    executeProvider(claim);
                    claimScan.advance(plan, candidateCursor);
                    claimScan.processed(plan);
                    return true;
                }
            } catch (Throwable failure) {
                if (!permanentCandidateFailure(failure)
                        && !claimScan.registerRetry(new RetryAttempt(candidate))) {
                    claimScan.rememberBlocked(predecessor, candidateCursor);
                    if (plan.kind() == ScanKind.BLOCKED) claimScan.blockedFailureObserved();
                }
            }
            current = candidateCursor;
            claimScan.advance(plan, current);
            advanced = true;
        }
        claimScan.finish(plan, !advanced || candidates.size() < CANDIDATE_BATCH
                || blockedBoundaryReached || (plan.kind() == ScanKind.BLOCKED
                && compareCursor(current, plan.through()) >= 0));
        return false;
    }

    synchronized int retryResponsibilities() { return retryBudget.used(); }

    private boolean permanentCandidateFailure(Throwable failure) {
        return failure instanceof ArchivePersonalDataException || failure instanceof IllegalArgumentException;
    }

    private int compareCursor(ScanCursor left, ScanCursor right) {
        if (right == null) return -1;
        if (left.candidateAt() == null) return right.candidateAt() == null
                ? Long.compare(left.rowId(), right.rowId()) : -1;
        if (right.candidateAt() == null) return 1;
        int time = left.candidateAt().compareTo(right.candidateAt());
        return time != 0 ? time : Long.compare(left.rowId(), right.rowId());
    }

    private boolean afterCursor(ClaimCandidate candidate, Instant cursorAt, long cursorRowId) {
        if (candidate == null || candidate.candidateAt() == null || candidate.rowId() <= 0) return false;
        if (cursorAt == null) return true;
        int timeOrder = candidate.candidateAt().compareTo(cursorAt);
        return timeOrder > 0 || (timeOrder == 0 && candidate.rowId() > cursorRowId);
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
            long version = ArchiveWire.increment(question.version());
            long sequence = ArchiveWire.increment(question.currentSequence());
            QuestionRecord running = ArchiveQuestionServiceImpl.change(question, "RUNNING", "", attempt - 1,
                    null, version, sequence, now, null);
            require(store.updateQuestion(candidate.owner(), running, question.version(),
                    question.currentSequence()) == 1, "Question claim CAS failed");
            OutboxRecord leased = new OutboxRecord(outbox.rowId(), question.questionId(), "LEASED", attempt,
                    token, outbox.publishedSequence(), now, now.plus(leaseDuration), null,
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
        LeaseHeartbeat heartbeat = startHeartbeat(claim);
        if (heartbeat == null) return;
        try {
            if (!provider.available()) {
                failIfCurrent(heartbeat, claim, "QUESTION_PROVIDER_UNAVAILABLE", true);
                return;
            }
            ArchiveQuestionProvider.Answer answer = provider.answer(new ArchiveQuestionProvider.Request(
                    claim.questionId(), claim.question(), claim.selectedText()));
            if (answer == null) {
                failIfCurrent(heartbeat, claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
                return;
            }
            int totalBytes = 0;
            StringBuilder completeAnswer = new StringBuilder();
            for (String delta : answer.deltas()) {
                if (!validUnicode(delta)) {
                    failIfCurrent(heartbeat, claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
                    return;
                }
                totalBytes = Math.addExact(totalBytes, delta.getBytes(StandardCharsets.UTF_8).length);
                if (totalBytes > ArchiveQuestionServiceImpl.MAX_ANSWER_BYTES) {
                    failIfCurrent(heartbeat, claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
                    return;
                }
                completeAnswer.append(delta);
            }
            if (!heartbeat.confirm()) return;
            persistAnswerAndComplete(claim, completeAnswer.toString());
        } catch (ArchiveQuestionProviderException failure) {
            failIfCurrent(heartbeat, claim, safeCode(failure.code()), failure.retryable());
        } catch (IllegalArgumentException | NullPointerException invalidResponse) {
            failIfCurrent(heartbeat, claim, "QUESTION_PROVIDER_RESPONSE_INVALID", false);
        } catch (ArchivePersonalDataException contractFailure) {
            throw contractFailure;
        } catch (Throwable failure) {
            failIfCurrent(heartbeat, claim, "QUESTION_PROVIDER_UNAVAILABLE", true);
        } finally {
            heartbeat.close();
        }
    }

    private void failIfCurrent(LeaseHeartbeat heartbeat, Claimed claim, String code, boolean retryable) {
        if (heartbeat.confirm()) fail(claim, code, retryable);
    }

    boolean renewLease(Claimed claim) {
        try {
            return Boolean.TRUE.equals(transactions.requiresNew(() -> {
                Instant now = clock.instant();
                return store.renewOutboxLease(claim.owner(), claim.questionId(), claim.fencingToken(),
                        now.plus(leaseDuration), now) == 1;
            }));
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean persistAnswerAndComplete(Claimed claim, String answer) {
        List<String> deltas = chunks(answer);
        require(!deltas.isEmpty(), "Provider answer is empty");
        return transactions.required(() -> {
            QuestionRecord question = store.findQuestion(claim.owner(), claim.questionId(), true);
            if (question == null) return false;
            OutboxRecord outbox = store.findOutbox(claim.owner(), claim.questionId(), true);
            if (!currentLease(outbox, claim.fencingToken()) || !"RUNNING".equals(question.status())) return false;
            require(answer.getBytes(StandardCharsets.UTF_8).length <= ArchiveQuestionServiceImpl.MAX_ANSWER_BYTES,
                    "Persisted answer exceeds limit");
            Instant now = clock.instant();
            long version = ArchiveWire.increment(question.version());
            long finalSequence = ArchiveWire.increment(question.currentSequence(), deltas.size() + 1L);
            QuestionRecord succeeded = ArchiveQuestionServiceImpl.change(question, "SUCCEEDED", answer,
                    question.retryCount(), null, version, finalSequence, now, now);
            require(store.updateQuestion(claim.owner(), succeeded, question.version(), question.currentSequence()) == 1,
                    "Question completion CAS failed");
            OutboxRecord done = new OutboxRecord(outbox.rowId(), question.questionId(), "DONE",
                    outbox.attemptCount(), outbox.fencingToken(), outbox.publishedSequence(), now,
                    null, null, outbox.createdAt(), now);
            require(store.updateOutbox(claim.owner(), done, claim.fencingToken(), "LEASED") == 1,
                    "Outbox completion fencing failed");
            long sequence = question.currentSequence();
            List<EventRecord> events = new ArrayList<>(deltas.size() + 1);
            for (String delta : deltas) {
                EventRecord event = event(question.questionId(), ++sequence, "ANSWER_DELTA",
                        Map.of("delta", delta), now);
                store.insertEvent(claim.owner(), event);
                events.add(event);
            }
            EventRecord succeededEvent = event(question.questionId(), ++sequence, "QUESTION_SUCCEEDED",
                    Map.of("status", "SUCCEEDED"), now);
            store.insertEvent(claim.owner(), succeededEvent);
            events.add(succeededEvent);
            for (EventRecord event : events) {
                transactions.afterCommit(() -> delivery.afterCommit(claim.owner(), event));
            }
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
                long version = ArchiveWire.increment(question.version());
                long sequence = ArchiveWire.increment(question.currentSequence());
                QuestionRecord failed = ArchiveQuestionServiceImpl.change(question, status, "",
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

    private boolean finalizeExpired(ClaimCandidate candidate) {
        return Boolean.TRUE.equals(transactions.required(() -> {
            QuestionRecord question = store.findQuestion(candidate.owner(), candidate.questionId(), true);
            if (question == null) return false;
            OutboxRecord outbox = store.findOutbox(candidate.owner(), candidate.questionId(), true);
            Instant now = clock.instant();
            if (outbox == null || !"LEASED".equals(outbox.state()) || outbox.attemptCount() < 3
                    || outbox.leaseUntil() == null || outbox.leaseUntil().isAfter(now)
                    || !"RUNNING".equals(question.status())) return false;
            Claimed claim = new Claimed(candidate.owner(), candidate.questionId(), outbox.fencingToken(),
                    outbox.attemptCount(), question.questionText(), question.selectedText());
            failInsideTransaction(claim, question, outbox, "QUESTION_PROVIDER_UNAVAILABLE", now);
            return true;
        }));
    }

    private void failInsideTransaction(Claimed claim, QuestionRecord question, OutboxRecord outbox,
                                       String code, Instant now) {
        long version = ArchiveWire.increment(question.version());
        long sequence = ArchiveWire.increment(question.currentSequence());
        QuestionRecord failed = ArchiveQuestionServiceImpl.change(question, "FAILED_FINAL", "",
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

    private LeaseHeartbeat startHeartbeat(Claimed claim) {
        try {
            LeaseHeartbeat heartbeat = new LeaseHeartbeat(claim);
            heartbeat.start(leaseScheduler());
            return heartbeat;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private ScheduledExecutorService leaseScheduler() {
        synchronized (executorLock) {
            if (leaseExecutor == null || leaseExecutor.isShutdown()) {
                leaseExecutor = daemonScheduler("archive-question-lease-renewer");
            }
            return leaseExecutor;
        }
    }

    private ScheduledExecutorService daemonScheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
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

    private List<String> chunks(String value) {
        List<String> result = new ArrayList<>();
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

    private long nextFence(long current) {
        if (current == Long.MAX_VALUE) {
            throw new ArchivePersonalDataException(409, "FENCING_TOKEN_EXHAUSTED",
                    "Question fencing token is exhausted", Long.toString(current));
        }
        return current + 1;
    }

    private Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @PreDestroy
    public void stop() {
        started.set(false);
        synchronized (executorLock) {
            ScheduledExecutorService current = executor;
            executor = null;
            if (current != null) current.shutdownNow();
            ScheduledExecutorService currentLease = leaseExecutor;
            leaseExecutor = null;
            if (currentLease != null) currentLease.shutdownNow();
        }
    }

    private final class LeaseHeartbeat implements AutoCloseable {
        private final Claimed claim;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private ScheduledFuture<?> future;

        private LeaseHeartbeat(Claimed claim) { this.claim = claim; }

        private void start(ScheduledExecutorService scheduler) {
            future = scheduler.scheduleWithFixedDelay(this::renew, renewInterval.toMillis(),
                    renewInterval.toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized void renew() {
            if (closed.get() || lost.get()) return;
            if (!renewLease(claim)) lost.set(true);
        }

        private synchronized boolean confirm() {
            if (closed.get() || lost.get()) return false;
            ScheduledFuture<?> scheduled = future;
            future = null;
            if (scheduled != null) scheduled.cancel(false);
            boolean current = renewLease(claim);
            if (!current) lost.set(true);
            return current;
        }

        @Override public synchronized void close() {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> scheduled = future;
            future = null;
            if (scheduled != null) scheduled.cancel(true);
        }
    }

    private static final class RetryBudget {
        private final int capacity;
        private int used;
        private RetryBudget(int capacity) { this.capacity = capacity; }
        private boolean reserve() {
            if (used >= capacity) return false;
            used++;
            return true;
        }
        private void release() {
            if (used <= 0) throw new IllegalStateException("Retry budget underflow");
            used--;
        }
        private int used() { return used; }
    }

    private static final class QueueScanState {
        private final RetryBudget budget;
        private ScanCursor forward = ScanCursor.START;
        private ScanCursor sweep = ScanCursor.START;
        private ScanCursor blockedStart;
        private ScanCursor blockedThrough;
        private ScanCursor blockedCursor;
        private ScanCursor blockedCycleThrough;
        private boolean blockedCycleFailed;
        private int planSequence;
        private final ArrayDeque<RetryAttempt> retries = new ArrayDeque<>();
        private final Set<RetryKey> retryKeys = new HashSet<>();

        private QueueScanState(RetryBudget budget) { this.budget = budget; }

        private ScanPlan begin() {
            int slot = Math.floorMod(planSequence++, SWEEP_INTERVAL_PAGES);
            if (slot == SWEEP_INTERVAL_PAGES - 1) {
                return new ScanPlan(ScanKind.SWEEP, sweep, null);
            }
            if (slot == SWEEP_INTERVAL_PAGES - 2 && blockedStart != null) {
                if (blockedCursor == null) blockedCursor = blockedStart;
                if (blockedCycleThrough == null) blockedCycleThrough = blockedThrough;
                return new ScanPlan(ScanKind.BLOCKED, blockedCursor, blockedCycleThrough);
            }
            return new ScanPlan(ScanKind.FORWARD, forward, null);
        }

        private void advance(ScanPlan plan, ScanCursor cursor) {
            switch (plan.kind()) {
                case FORWARD -> forward = cursor;
                case SWEEP -> sweep = cursor;
                case BLOCKED -> blockedCursor = cursor;
            }
        }

        private RetryAttempt pollRetry() { return retries.pollFirst(); }

        private boolean registerRetry(RetryAttempt retry) {
            RetryKey key = RetryKey.of(retry.candidate());
            if (retryKeys.contains(key)) return true;
            if (!budget.reserve()) return false;
            retryKeys.add(key);
            retries.addLast(retry);
            return true;
        }

        private void retryFailed(RetryAttempt retry) {
            RetryKey key = RetryKey.of(retry.candidate());
            if (retryKeys.contains(key)) retries.addLast(retry);
            else registerRetry(retry);
        }

        private void retryResolved(RetryAttempt retry) {
            if (retryKeys.remove(RetryKey.of(retry.candidate()))) budget.release();
        }

        private void rememberBlocked(ScanCursor predecessor, ScanCursor candidate) {
            boolean earlierStart = blockedStart == null || compare(predecessor, blockedStart) < 0;
            if (earlierStart) blockedStart = predecessor;
            if (blockedThrough == null || compare(candidate, blockedThrough) > 0) blockedThrough = candidate;
            if (blockedCursor == null || earlierStart) {
                blockedCursor = blockedStart;
                blockedCycleThrough = blockedThrough;
                blockedCycleFailed = false;
            }
        }

        private void blockedFailureObserved() { blockedCycleFailed = true; }

        private int compare(ScanCursor left, ScanCursor right) {
            if (left.candidateAt() == null) return right.candidateAt() == null
                    ? Long.compare(left.rowId(), right.rowId()) : -1;
            if (right.candidateAt() == null) return 1;
            int time = left.candidateAt().compareTo(right.candidateAt());
            return time != 0 ? time : Long.compare(left.rowId(), right.rowId());
        }

        private void processed(ScanPlan plan) {
            // A row can become eligible again (explicit retry / lease expiry), so restart normal lookup.
            // Sweep, bounded blocked interval, and exact failed-row responsibilities remain independent.
            forward = ScanCursor.START;
        }

        private void processedRetry() { forward = ScanCursor.START; }

        private void finish(ScanPlan plan, boolean endReached) {
            if (!endReached) return;
            switch (plan.kind()) {
                case FORWARD -> forward = ScanCursor.START;
                case SWEEP -> sweep = ScanCursor.START;
                case BLOCKED -> {
                    if (blockedCycleFailed) {
                        blockedCursor = blockedStart;
                        blockedCycleThrough = blockedThrough;
                        blockedCycleFailed = false;
                    } else {
                        blockedStart = null;
                        blockedThrough = null;
                        blockedCursor = null;
                        blockedCycleThrough = null;
                    }
                }
            }
        }
    }

    private record RetryAttempt(ClaimCandidate candidate) {
        private RetryAttempt {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    private record RetryKey(ArchiveOwnerScope owner, String questionId) {
        private static RetryKey of(ClaimCandidate candidate) {
            return new RetryKey(candidate.owner(), candidate.questionId());
        }
    }

    private enum ScanKind { FORWARD, SWEEP, BLOCKED }

    private record ScanPlan(ScanKind kind, ScanCursor cursor, ScanCursor through) { }

    private record ScanCursor(Instant candidateAt, long rowId) {
        private static final ScanCursor START = new ScanCursor(null, 0);
    }

    record Claimed(ArchiveOwnerScope owner, String questionId, long fencingToken, int attempt,
                   String question, String selectedText) { }
}
