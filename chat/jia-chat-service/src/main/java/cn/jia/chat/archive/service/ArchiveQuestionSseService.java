package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.dto.ArchiveQuestionEventDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionSseService {
    static final int BUFFER_LIMIT = 256;
    static final int OUTBOUND_LIMIT = 64;
    static final int REPLAY_BATCH = 100;
    static final int MAX_ACTIVE_SESSIONS = 32;
    static final int MAX_OWNER_ACTIVE_SESSIONS = 8;
    static final int MAX_PENDING_REPLAYS = 16;
    static final int MAX_OWNER_PENDING_REPLAYS = 8;
    static final int MAX_OUTBOUND_WRITERS = 32;
    static final int MAX_OWNER_OUTBOUND_WRITERS = 8;
    static final int MAX_PENDING_COMPLETIONS = 16;
    static final int MAX_OWNER_PENDING_COMPLETIONS = 4;
    static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();
    static final long HEARTBEAT_SECONDS = 15;
    private static final int REPLAY_THREADS = 4;
    private static final int REPLAY_QUEUE = MAX_PENDING_REPLAYS - REPLAY_THREADS;
    private static final int COMPLETION_THREADS = 4;
    private static final int COMPLETION_QUEUE = MAX_PENDING_COMPLETIONS - COMPLETION_THREADS;
    private static final AtomicInteger WRITER_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger COMPLETION_SEQUENCE = new AtomicInteger();

    private final ArchiveQuestionStore store;
    private final ArchiveQuestionEventBroker broker;
    private final ArchiveQuestionAccessPolicy accessPolicy;
    private final ArchiveWriteJson json = new ArchiveWriteJson();
    private final ExecutorService replayExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ExecutorService completionExecutor;
    private final OutboundFactory outboundFactory;
    private final Admission admission;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Set<CompletionWork> completionWorks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                                     ArchiveQuestionAccessPolicy accessPolicy) {
        this(store, broker, accessPolicy, replayExecutor(),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        runnable -> daemon(runnable, "archive-question-heartbeat")),
                (ignored, terminated) -> new SerialOutbound(terminated), AdmissionLimits.production(),
                completionExecutor());
    }

    ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                              ArchiveQuestionAccessPolicy accessPolicy, ExecutorService replayExecutor,
                              ScheduledExecutorService heartbeatExecutor) {
        this(store, broker, accessPolicy, replayExecutor, heartbeatExecutor,
                (ignored, terminated) -> new SerialOutbound(terminated), AdmissionLimits.production(),
                completionExecutor());
    }

    ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                              ArchiveQuestionAccessPolicy accessPolicy, ExecutorService replayExecutor,
                              ScheduledExecutorService heartbeatExecutor, OutboundFactory outboundFactory) {
        this(store, broker, accessPolicy, replayExecutor, heartbeatExecutor,
                outboundFactory, AdmissionLimits.production(), completionExecutor());
    }

    ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                              ArchiveQuestionAccessPolicy accessPolicy, ExecutorService replayExecutor,
                              ScheduledExecutorService heartbeatExecutor, OutboundFactory outboundFactory,
                              AdmissionLimits limits) {
        this(store, broker, accessPolicy, replayExecutor, heartbeatExecutor, outboundFactory, limits,
                completionExecutor());
    }

    ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                              ArchiveQuestionAccessPolicy accessPolicy, ExecutorService replayExecutor,
                              ScheduledExecutorService heartbeatExecutor, OutboundFactory outboundFactory,
                              AdmissionLimits limits, ExecutorService completionExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.replayExecutor = Objects.requireNonNull(replayExecutor, "replayExecutor");
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
        this.outboundFactory = Objects.requireNonNull(outboundFactory, "outboundFactory");
        this.admission = new Admission(Objects.requireNonNull(limits, "limits"));
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
    }

    public SseEmitter open(ArchiveOwnerScope owner, String questionId, long cursor) {
        ManagedSseEmitter emitter = new ManagedSseEmitter(SSE_TIMEOUT_MILLIS);
        try {
            subscribe(owner, questionId, cursor, new EmitterSink(emitter));
            return emitter;
        } catch (Throwable failure) {
            emitter.cancel();
            throw failure;
        }
    }

    StreamHandle subscribe(ArchiveOwnerScope owner, String questionId, long cursor, Sink sink) {
        if (!ArchiveWire.lowercaseUuid(questionId)) notFound();
        if (cursor < 0) throw new ArchivePersonalDataException(400, "INVALID_EVENT_CURSOR",
                "Last-Event-ID must be a canonical nonnegative decimal");
        if (owner == null || !accessPolicy.allows(owner.tenantId(), owner.clientId())) notFound();
        Objects.requireNonNull(sink, "sink");
        QuestionRecord visible = store.findQuestion(owner, questionId, false);
        if (visible == null) notFound();
        if (stopped.get()) throw rateLimited();
        AdmissionLease lease = admission.acquire(owner);
        if (lease == null) throw rateLimited();
        Outbound outbound;
        try {
            outbound = Objects.requireNonNull(
                    outboundFactory.create(questionId, lease::releaseOutbound), "outbound");
        } catch (Throwable failure) {
            lease.releaseAll();
            throw failure;
        }
        if (sink instanceof EmitterSink emitterSink) {
            emitterSink.setCompletionScheduler(action -> scheduleCompletion(owner, action));
        }
        Session session = new Session(owner, questionId, cursor, sink, outbound, lease);
        try {
            session.start();
            return session;
        } catch (RejectedExecutionException rejected) {
            throw rateLimited();
        }
    }

    int activeSubscriptions() { return broker.activeSubscriptions(); }
    int admittedSessions() { return admission.active(); }
    int pendingReplays() { return admission.replays(); }
    int admittedOutboundWriters() { return admission.outbounds(); }
    int pendingCompletions() { return admission.completions(); }

    interface Sink {
        void onClose(Runnable cleanup);
        boolean persisted(ArchiveQuestionEventDTO event);
        boolean resync(String questionId);
        boolean heartbeat();
        void complete();
        void cancel();
        boolean closed();
    }

    interface StreamHandle extends AutoCloseable {
        boolean closed();
        @Override void close();
    }

    @FunctionalInterface
    interface OutboundFactory {
        Outbound create(String questionId, Runnable onTerminated);
    }

    interface Outbound {
        boolean submit(Runnable action);
        boolean terminal(Runnable action);
        void cancel();
    }

    static Outbound serialOutbound(Runnable onTerminated) {
        return new SerialOutbound(onTerminated);
    }

    static Outbound directOutbound(Runnable onTerminated) {
        Objects.requireNonNull(onTerminated, "onTerminated");
        return new Outbound() {
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicBoolean terminated = new AtomicBoolean();
            private void terminate() {
                if (terminated.compareAndSet(false, true)) onTerminated.run();
            }
            @Override public boolean submit(Runnable action) {
                if (cancelled.get()) return false;
                action.run();
                return !cancelled.get();
            }
            @Override public boolean terminal(Runnable action) {
                if (!cancelled.compareAndSet(false, true)) return false;
                try { action.run(); }
                finally { terminate(); }
                return true;
            }
            @Override public void cancel() {
                cancelled.set(true);
                terminate();
            }
        };
    }

    private final class Session implements StreamHandle {
        private final ArchiveOwnerScope owner;
        private final String questionId;
        private final long cursor;
        private final Sink sink;
        private final Outbound outbound;
        private final AdmissionLease admissionLease;
        private final Object lock = new Object();
        private final TreeMap<Long, EventRecord> buffer = new TreeMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private ArchiveQuestionEventBroker.Subscription live;
        private ReplayWork replay;
        private ScheduledFuture<?> heartbeat;
        private long delivered;
        private boolean direct;

        private Session(ArchiveOwnerScope owner, String questionId, long cursor, Sink sink,
                        Outbound outbound, AdmissionLease admissionLease) {
            this.owner = owner;
            this.questionId = questionId;
            this.cursor = cursor;
            this.sink = Objects.requireNonNull(sink, "sink");
            this.outbound = Objects.requireNonNull(outbound, "outbound");
            this.admissionLease = Objects.requireNonNull(admissionLease, "admissionLease");
            this.delivered = cursor;
        }

        private void start() {
            sessions.add(this);
            try {
                sink.onClose(this::abort);
                synchronized (lock) {
                    if (closed.get() || sink.closed()) { abort(); return; }
                    live = broker.subscribe(owner, questionId, this::observe);
                    heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                            this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                    ReplayWork work = new ReplayWork(this::initializeReplay, admissionLease::releaseReplay);
                    replay = work;
                    replayExecutor.execute(work);
                }
            } catch (Throwable failure) {
                abort();
                throw failure;
            }
        }

        /** Broker callback: state/buffer work only; network writes are per-session outbound tasks. */
        private void observe(EventRecord event) {
            synchronized (lock) {
                if (closed.get() || sink.closed() || event == null || !questionId.equals(event.questionId())) return;
                if (event.sequence() <= delivered) return;
                if (!direct) {
                    buffer.putIfAbsent(event.sequence(), event);
                    if (buffer.size() > BUFFER_LIMIT) resyncLocked();
                    return;
                }
                if (event.sequence() != delivered + 1) { resyncLocked(); return; }
                if (enqueuePersistedLocked(event)) delivered = event.sequence();
            }
        }

        private void initializeReplay() {
            try {
                if (closed.get() || sink.closed()) return;
                QuestionRecord snapshot = store.findQuestion(owner, questionId, false);
                if (snapshot == null) { abort(); return; }
                long watermark = snapshot.currentSequence();
                if (cursor > watermark || truncated(cursor, watermark)) { resync(); return; }
                long after = cursor;
                while (after < watermark && !closed.get()) {
                    List<EventRecord> events = store.listEvents(owner, questionId, after, watermark, REPLAY_BATCH);
                    if (events.isEmpty()) { resync(); return; }
                    for (EventRecord event : events) {
                        synchronized (lock) {
                            if (closed.get() || sink.closed()) return;
                            if (event.sequence() != delivered + 1 || event.sequence() > watermark) {
                                resyncLocked(); return;
                            }
                            if (!enqueuePersistedLocked(event)) return;
                            delivered = event.sequence();
                            buffer.remove(event.sequence());
                            after = delivered;
                        }
                    }
                }
                synchronized (lock) {
                    if (closed.get() || sink.closed()) return;
                    if (delivered != watermark) { resyncLocked(); return; }
                    buffer.headMap(delivered, true).clear();
                    while (!buffer.isEmpty()) {
                        Map.Entry<Long, EventRecord> next = buffer.firstEntry();
                        if (next.getKey() != delivered + 1) { resyncLocked(); return; }
                        buffer.pollFirstEntry();
                        if (!enqueuePersistedLocked(next.getValue())) return;
                        delivered = next.getKey();
                    }
                    direct = true;
                }
            } catch (Throwable failure) {
                if (!Thread.currentThread().isInterrupted()) resync();
            }
        }

        private boolean truncated(long after, long watermark) {
            if (after == watermark) return false;
            Long earliest = store.earliestEventSequence(owner, questionId);
            return earliest == null || after < earliest - 1;
        }

        @SuppressWarnings("unchecked")
        private boolean enqueuePersistedLocked(EventRecord event) {
            Map<String, Object> payload = json.readValue(event.payloadJson(), Map.class);
            ArchiveQuestionEventCatalog.validate(event.eventType(), payload);
            ArchiveQuestionEventDTO dto = new ArchiveQuestionEventDTO(1, questionId,
                    Long.toString(event.sequence()), event.eventType(), event.occurredAt().toString(), payload);
            if (outbound.submit(() -> {
                try {
                    if (!sink.closed() && !sink.persisted(dto)) abort();
                } catch (Throwable failure) {
                    abort();
                }
            })) return true;
            resyncLocked();
            return false;
        }

        private void heartbeat() {
            synchronized (lock) {
                if (closed.get() || sink.closed()) return;
                if (!accessPolicy.allows(owner.tenantId(), owner.clientId())) {
                    completeLocked();
                    return;
                }
                if (!outbound.submit(() -> {
                    try {
                        if (!sink.closed() && !sink.heartbeat()) abort();
                    } catch (Throwable failure) {
                        abort();
                    }
                })) resyncLocked();
            }
        }

        private void resync() { synchronized (lock) { resyncLocked(); } }

        private void resyncLocked() {
            if (!beginCloseLocked()) return;
            boolean accepted = outbound.terminal(() -> {
                try {
                    if (!sink.closed()) sink.resync(questionId);
                } finally {
                    sink.complete();
                }
            });
            if (!accepted) sink.cancel();
        }

        private void completeLocked() {
            if (!beginCloseLocked()) return;
            if (!outbound.terminal(sink::complete)) sink.cancel();
        }

        private boolean beginCloseLocked() {
            if (!closed.compareAndSet(false, true)) return false;
            direct = false;
            buffer.clear();
            ArchiveQuestionEventBroker.Subscription currentLive = live;
            live = null;
            if (currentLive != null) currentLive.close();
            ReplayWork currentReplay = replay;
            replay = null;
            if (currentReplay != null) {
                currentReplay.cancel();
                if (replayExecutor instanceof ThreadPoolExecutor pool) pool.remove(currentReplay);
            } else admissionLease.releaseReplay();
            ScheduledFuture<?> currentHeartbeat = heartbeat;
            heartbeat = null;
            if (currentHeartbeat != null) currentHeartbeat.cancel(true);
            sessions.remove(this);
            admissionLease.releaseActive();
            return true;
        }

        private void abort() {
            synchronized (lock) {
                beginCloseLocked();
            }
            // Explicit disconnect/timeout/stop overrides queued output and never waits for a blocked send.
            outbound.cancel();
            sink.cancel();
        }

        @Override public void close() { abort(); }
        @Override public boolean closed() { return closed.get(); }
    }

    private static final class ReplayWork implements Runnable {
        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int RELEASED = 2;
        private final Runnable action;
        private final Runnable onReleased;
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread runner;

        private ReplayWork(Runnable action, Runnable onReleased) {
            this.action = Objects.requireNonNull(action, "action");
            this.onReleased = Objects.requireNonNull(onReleased, "onReleased");
        }

        @Override public void run() {
            if (!state.compareAndSet(PENDING, RUNNING)) return;
            runner = Thread.currentThread();
            try {
                if (!cancelled.get()) action.run();
            } finally {
                runner = null;
                if (state.compareAndSet(RUNNING, RELEASED)) onReleased.run();
            }
        }

        private void cancel() {
            cancelled.set(true);
            if (state.compareAndSet(PENDING, RELEASED)) {
                onReleased.run();
                return;
            }
            Thread current = runner;
            if (current != null && current != Thread.currentThread()) current.interrupt();
        }
    }

    private static final class SerialOutbound implements Outbound {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final ThreadPoolExecutor executor;

        private SerialOutbound(Runnable onTerminated) {
            Objects.requireNonNull(onTerminated, "onTerminated");
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(OUTBOUND_LIMIT), runnable -> daemon(runnable,
                    "archive-question-outbound-" + WRITER_SEQUENCE.incrementAndGet()),
                    new ThreadPoolExecutor.AbortPolicy()) {
                @Override protected void terminated() {
                    try { onTerminated.run(); }
                    finally { super.terminated(); }
                }
            };
        }

        @Override public synchronized boolean submit(Runnable action) {
            if (cancelled.get() || terminal.get()) return false;
            try {
                executor.execute(() -> {
                    if (cancelled.get()) return;
                    try { action.run(); }
                    catch (Throwable ignored) { /* Session task owns cleanup; never kill the writer. */ }
                });
                return true;
            } catch (RejectedExecutionException rejected) {
                return false;
            }
        }

        @Override public synchronized boolean terminal(Runnable action) {
            if (cancelled.get() || !terminal.compareAndSet(false, true)) return false;
            executor.getQueue().clear();
            try {
                executor.execute(() -> {
                    if (cancelled.get()) return;
                    try { action.run(); }
                    finally { executor.shutdown(); }
                });
                return true;
            } catch (RejectedExecutionException rejected) {
                executor.shutdownNow();
                return false;
            }
        }

        @Override public synchronized void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            executor.getQueue().clear();
            executor.shutdownNow();
        }
    }

    static final class EmitterSink implements Sink {
        private final ManagedSseEmitter emitter;
        EmitterSink(ManagedSseEmitter emitter) { this.emitter = emitter; }
        void setCompletionScheduler(CompletionScheduler scheduler) {
            emitter.setCompletionScheduler(scheduler);
        }
        @Override public void onClose(Runnable cleanup) {
            emitter.setCleanup(cleanup);
            emitter.onCompletion(emitter::cancelFromContainer);
            emitter.onTimeout(emitter::cancelFromContainer);
            emitter.onError(ignored -> emitter.cancelFromContainer());
        }
        @Override public boolean persisted(ArchiveQuestionEventDTO dto) {
            return emitter.sendIfOpen(SseEmitter.event().id(dto.sequence()).name(dto.type())
                    .data(dto, MediaType.APPLICATION_JSON));
        }
        @Override public boolean resync(String questionId) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemaVersion", 1);
            data.put("questionId", questionId);
            data.put("type", "resync_required");
            return emitter.sendIfOpen(SseEmitter.event().name("resync_required")
                    .data(data, MediaType.APPLICATION_JSON));
        }
        @Override public boolean heartbeat() {
            return emitter.sendIfOpen(SseEmitter.event().comment("heartbeat"));
        }
        @Override public void complete() { emitter.complete(); }
        @Override public void cancel() { emitter.cancel(); }
        @Override public boolean closed() { return emitter.closed(); }
    }

    static class ManagedSseEmitter extends SseEmitter {
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean transportCompletionStarted = new AtomicBoolean();
        private final AtomicReference<Runnable> cleanup = new AtomicReference<>(() -> { });
        private final AtomicReference<CompletionScheduler> completionScheduler =
                new AtomicReference<>(ignored -> false);

        ManagedSseEmitter(long timeout) { super(timeout); }

        void setCleanup(Runnable cleanup) { this.cleanup.set(Objects.requireNonNull(cleanup)); }

        void setCompletionScheduler(CompletionScheduler scheduler) {
            completionScheduler.set(Objects.requireNonNull(scheduler, "scheduler"));
        }

        boolean sendIfOpen(SseEventBuilder event) {
            if (finished.get()) return false;
            try {
                send(event);
                return !finished.get();
            } catch (IOException | RuntimeException failure) {
                finish(failure, true);
                return false;
            }
        }

        @Override public void complete() { finish(null, true); }

        @Override public void completeWithError(Throwable error) {
            finish(Objects.requireNonNull(error, "error"), true);
        }

        void cancelFromContainer() { finish(null, false); }

        void cancel() {
            finish(new IOException("Archive question event stream cancelled"), true);
        }

        private void finish(Throwable error, boolean completeTransport) {
            if (!finished.compareAndSet(false, true)) return;
            runCleanup();
            if (completeTransport) scheduleTransportCompletion(error);
        }

        private void scheduleTransportCompletion(Throwable error) {
            if (!transportCompletionStarted.compareAndSet(false, true)) return;
            try {
                completionScheduler.get().submit(() -> {
                    try { completeTransport(error); }
                    catch (Throwable ignored) { /* Logical cleanup already completed; transport is best effort. */ }
                });
            } catch (Throwable ignored) {
                // Logical cleanup is authoritative; executor/admission failures never escape close callbacks.
            }
        }

        void completeTransport(Throwable error) {
            if (error == null) super.complete();
            else super.completeWithError(error);
        }

        private void runCleanup() {
            try { cleanup.get().run(); }
            catch (Throwable ignored) { /* Container lifecycle must remain no-throw. */ }
        }

        boolean closed() { return finished.get(); }
    }

    record AdmissionLimits(int activeGlobal, int activeOwner, int replayGlobal, int replayOwner,
                           int outboundGlobal, int outboundOwner,
                           int completionGlobal, int completionOwner) {
        AdmissionLimits {
            if (activeGlobal < 1 || activeOwner < 1 || replayGlobal < 1 || replayOwner < 1
                    || outboundGlobal < 1 || outboundOwner < 1
                    || completionGlobal < 1 || completionOwner < 1
                    || activeOwner > activeGlobal || replayOwner > replayGlobal
                    || outboundOwner > outboundGlobal || completionOwner > completionGlobal) {
                throw new IllegalArgumentException("SSE admission limits must be positive owner/global bounds");
            }
        }
        static AdmissionLimits production() {
            return new AdmissionLimits(MAX_ACTIVE_SESSIONS, MAX_OWNER_ACTIVE_SESSIONS,
                    MAX_PENDING_REPLAYS, MAX_OWNER_PENDING_REPLAYS,
                    MAX_OUTBOUND_WRITERS, MAX_OWNER_OUTBOUND_WRITERS,
                    MAX_PENDING_COMPLETIONS, MAX_OWNER_PENDING_COMPLETIONS);
        }
    }

    private static final class Admission {
        private final AdmissionLimits limits;
        private final Map<ArchiveOwnerScope, Counts> owners = new HashMap<>();
        private int active;
        private int replays;
        private int outbounds;
        private int completions;

        private Admission(AdmissionLimits limits) { this.limits = limits; }

        private synchronized AdmissionLease acquire(ArchiveOwnerScope owner) {
            Counts counts = owners.computeIfAbsent(owner, ignored -> new Counts());
            if (active >= limits.activeGlobal() || counts.active >= limits.activeOwner()
                    || replays >= limits.replayGlobal() || counts.replays >= limits.replayOwner()
                    || outbounds >= limits.outboundGlobal() || counts.outbounds >= limits.outboundOwner()) {
                removeIfEmpty(owner, counts);
                return null;
            }
            active++;
            replays++;
            outbounds++;
            counts.active++;
            counts.replays++;
            counts.outbounds++;
            return new AdmissionLease(this, owner);
        }

        private synchronized void release(ArchiveOwnerScope owner, Resource resource) {
            Counts counts = owners.get(owner);
            if (counts == null) return;
            switch (resource) {
                case ACTIVE -> { if (counts.active > 0) { counts.active--; active--; } }
                case REPLAY -> { if (counts.replays > 0) { counts.replays--; replays--; } }
                case OUTBOUND -> { if (counts.outbounds > 0) { counts.outbounds--; outbounds--; } }
                case COMPLETION -> { if (counts.completions > 0) { counts.completions--; completions--; } }
            }
            removeIfEmpty(owner, counts);
        }

        private void removeIfEmpty(ArchiveOwnerScope owner, Counts counts) {
            if (counts.active == 0 && counts.replays == 0 && counts.outbounds == 0
                    && counts.completions == 0) owners.remove(owner);
        }

        private synchronized int active() { return active; }
        private synchronized int replays() { return replays; }
        private synchronized CompletionPermit acquireCompletion(ArchiveOwnerScope owner) {
            Counts counts = owners.computeIfAbsent(owner, ignored -> new Counts());
            if (completions >= limits.completionGlobal()
                    || counts.completions >= limits.completionOwner()) {
                removeIfEmpty(owner, counts);
                return null;
            }
            completions++;
            counts.completions++;
            return new CompletionPermit(this, owner);
        }

        private synchronized int outbounds() { return outbounds; }
        private synchronized int completions() { return completions; }
    }

    private static final class AdmissionLease {
        private final Admission admission;
        private final ArchiveOwnerScope owner;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean replay = new AtomicBoolean(true);
        private final AtomicBoolean outbound = new AtomicBoolean(true);

        private AdmissionLease(Admission admission, ArchiveOwnerScope owner) {
            this.admission = admission;
            this.owner = owner;
        }

        private void releaseActive() {
            if (active.compareAndSet(true, false)) admission.release(owner, Resource.ACTIVE);
        }

        private void releaseReplay() {
            if (replay.compareAndSet(true, false)) admission.release(owner, Resource.REPLAY);
        }

        private void releaseOutbound() {
            if (outbound.compareAndSet(true, false)) admission.release(owner, Resource.OUTBOUND);
        }

        private void releaseAll() {
            releaseActive();
            releaseReplay();
            releaseOutbound();
        }
    }

    private static final class CompletionPermit {
        private final Admission admission;
        private final ArchiveOwnerScope owner;
        private final AtomicBoolean held = new AtomicBoolean(true);
        private CompletionPermit(Admission admission, ArchiveOwnerScope owner) {
            this.admission = admission;
            this.owner = owner;
        }
        private void release() {
            if (held.compareAndSet(true, false)) admission.release(owner, Resource.COMPLETION);
        }
    }

    private static final class Counts {
        private int active;
        private int replays;
        private int outbounds;
        private int completions;
    }

    private enum Resource { ACTIVE, REPLAY, OUTBOUND, COMPLETION }

    private static ExecutorService replayExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(REPLAY_THREADS, REPLAY_THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(REPLAY_QUEUE), runnable -> daemon(runnable,
                "archive-question-replay-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ExecutorService completionExecutor() {
        return new ThreadPoolExecutor(COMPLETION_THREADS, COMPLETION_THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(COMPLETION_QUEUE), runnable -> daemon(runnable,
                "archive-question-completion-" + COMPLETION_SEQUENCE.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @FunctionalInterface
    interface CompletionScheduler {
        boolean submit(Runnable action);
    }

    private boolean scheduleCompletion(ArchiveOwnerScope owner, Runnable action) {
        CompletionPermit permit = admission.acquireCompletion(owner);
        if (permit == null) return false;
        CompletionWork work = new CompletionWork(action, permit);
        completionWorks.add(work);
        try {
            completionExecutor.execute(work);
            return true;
        } catch (RejectedExecutionException rejected) {
            work.cancel();
            return false;
        } catch (Throwable failure) {
            work.cancel();
            return false;
        }
    }

    private final class CompletionWork implements Runnable {
        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int RELEASED = 2;
        private final Runnable action;
        private final CompletionPermit permit;
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private volatile Thread runner;

        private CompletionWork(Runnable action, CompletionPermit permit) {
            this.action = Objects.requireNonNull(action, "action");
            this.permit = Objects.requireNonNull(permit, "permit");
        }

        @Override public void run() {
            if (!state.compareAndSet(PENDING, RUNNING)) return;
            runner = Thread.currentThread();
            try { action.run(); }
            finally {
                runner = null;
                if (state.compareAndSet(RUNNING, RELEASED)) release();
            }
        }

        private void cancel() {
            if (state.compareAndSet(PENDING, RELEASED)) {
                release();
                return;
            }
            Thread current = runner;
            if (current != null && current != Thread.currentThread()) current.interrupt();
        }

        private void release() {
            completionWorks.remove(this);
            permit.release();
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void notFound() {
        throw new ArchivePersonalDataException(404, "ARCHIVE_RESOURCE_NOT_FOUND",
                "Archive resource is not available");
    }

    private ArchivePersonalDataException rateLimited() {
        return new ArchivePersonalDataException(429, "QUESTION_RATE_LIMITED",
                "Archive question event stream limit exceeded");
    }

    @PreDestroy public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        for (Session session : List.copyOf(sessions)) session.abort();
        replayExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
        // Close admission immediately but let the already-bounded completion set drive accepted
        // servlet responses exactly once. No caller waits for these best-effort daemon tasks.
        completionExecutor.shutdown();
    }
}
