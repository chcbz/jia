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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();
    static final long HEARTBEAT_SECONDS = 15;
    private static final AtomicInteger WRITER_SEQUENCE = new AtomicInteger();

    private final ArchiveQuestionStore store;
    private final ArchiveQuestionEventBroker broker;
    private final ArchiveQuestionAccessPolicy accessPolicy;
    private final ArchiveWriteJson json = new ArchiveWriteJson();
    private final ExecutorService replayExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final OutboundFactory outboundFactory;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();

    public ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                                     ArchiveQuestionAccessPolicy accessPolicy) {
        this(store, broker, accessPolicy,
                Executors.newFixedThreadPool(4, runnable -> daemon(runnable, "archive-question-replay")),
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> daemon(runnable, "archive-question-heartbeat")),
                ignored -> new SerialOutbound());
    }

    ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                              ArchiveQuestionAccessPolicy accessPolicy, ExecutorService replayExecutor,
                              ScheduledExecutorService heartbeatExecutor) {
        this(store, broker, accessPolicy, replayExecutor, heartbeatExecutor,
                ignored -> new SerialOutbound());
    }

    ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                              ArchiveQuestionAccessPolicy accessPolicy, ExecutorService replayExecutor,
                              ScheduledExecutorService heartbeatExecutor, OutboundFactory outboundFactory) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.replayExecutor = Objects.requireNonNull(replayExecutor, "replayExecutor");
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
        this.outboundFactory = Objects.requireNonNull(outboundFactory, "outboundFactory");
    }

    public SseEmitter open(ArchiveOwnerScope owner, String questionId, long cursor) {
        ManagedSseEmitter emitter = new ManagedSseEmitter(SSE_TIMEOUT_MILLIS);
        subscribe(owner, questionId, cursor, new EmitterSink(emitter));
        return emitter;
    }

    StreamHandle subscribe(ArchiveOwnerScope owner, String questionId, long cursor, Sink sink) {
        if (!ArchiveWire.lowercaseUuid(questionId)) notFound();
        if (cursor < 0) throw new ArchivePersonalDataException(400, "INVALID_EVENT_CURSOR",
                "Last-Event-ID must be a canonical nonnegative decimal");
        if (!accessPolicy.allows(owner.tenantId(), owner.clientId())) notFound();
        QuestionRecord visible = store.findQuestion(owner, questionId, false);
        if (visible == null) notFound();
        Session session = new Session(owner, questionId, cursor, sink, outboundFactory.create(questionId));
        session.start();
        return session;
    }

    int activeSubscriptions() { return broker.activeSubscriptions(); }

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
        Outbound create(String questionId);
    }

    interface Outbound {
        boolean submit(Runnable action);
        boolean terminal(Runnable action);
        void cancel();
    }

    static Outbound directOutbound() {
        return new Outbound() {
            private final AtomicBoolean cancelled = new AtomicBoolean();
            @Override public boolean submit(Runnable action) {
                if (cancelled.get()) return false;
                action.run();
                return !cancelled.get();
            }
            @Override public boolean terminal(Runnable action) {
                if (!cancelled.compareAndSet(false, true)) return false;
                action.run();
                return true;
            }
            @Override public void cancel() { cancelled.set(true); }
        };
    }

    private final class Session implements StreamHandle {
        private final ArchiveOwnerScope owner;
        private final String questionId;
        private final long cursor;
        private final Sink sink;
        private final Outbound outbound;
        private final Object lock = new Object();
        private final TreeMap<Long, EventRecord> buffer = new TreeMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private ArchiveQuestionEventBroker.Subscription live;
        private Future<?> replay;
        private ScheduledFuture<?> heartbeat;
        private long delivered;
        private boolean direct;

        private Session(ArchiveOwnerScope owner, String questionId, long cursor, Sink sink, Outbound outbound) {
            this.owner = owner;
            this.questionId = questionId;
            this.cursor = cursor;
            this.sink = Objects.requireNonNull(sink, "sink");
            this.outbound = Objects.requireNonNull(outbound, "outbound");
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
                    replay = replayExecutor.submit(this::initializeReplay);
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
            Future<?> currentReplay = replay;
            replay = null;
            if (currentReplay != null) currentReplay.cancel(true);
            ScheduledFuture<?> currentHeartbeat = heartbeat;
            heartbeat = null;
            if (currentHeartbeat != null) currentHeartbeat.cancel(true);
            sessions.remove(this);
            return true;
        }

        private void abort() {
            synchronized (lock) {
                beginCloseLocked();
            }
            // Explicit disconnect/timeout/stop overrides a queued graceful terminal and never waits
            // for an in-flight network send. Both operations are idempotent and bounded.
            outbound.cancel();
            sink.cancel();
        }

        @Override public void close() { abort(); }
        @Override public boolean closed() { return closed.get(); }
    }

    private static final class SerialOutbound implements Outbound {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(OUTBOUND_LIMIT), runnable -> daemon(runnable,
                "archive-question-outbound-" + WRITER_SEQUENCE.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());

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
                return false;
            }
        }

        @Override public synchronized void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            executor.getQueue().clear();
            executor.shutdownNow();
        }
    }

    private static final class EmitterSink implements Sink {
        private final ManagedSseEmitter emitter;
        private EmitterSink(ManagedSseEmitter emitter) { this.emitter = emitter; }
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
        private final AtomicReference<Runnable> cleanup = new AtomicReference<>(() -> { });
        ManagedSseEmitter(long timeout) { super(timeout); }
        private void setCleanup(Runnable cleanup) { this.cleanup.set(Objects.requireNonNull(cleanup)); }
        private boolean sendIfOpen(SseEventBuilder event) {
            if (finished.get()) return false;
            try {
                send(event);
                return !finished.get();
            } catch (IOException | RuntimeException failure) {
                if (finished.compareAndSet(false, true)) {
                    runCleanup();
                    super.completeWithError(failure);
                }
                return false;
            }
        }
        @Override public void complete() {
            if (!finished.compareAndSet(false, true)) return;
            runCleanup();
            super.complete();
        }
        @Override public void completeWithError(Throwable error) {
            if (!finished.compareAndSet(false, true)) return;
            runCleanup();
            super.completeWithError(error);
        }
        private void cancelFromContainer() { cancel(); }
        private void cancel() {
            if (!finished.compareAndSet(false, true)) return;
            runCleanup();
        }
        private void runCleanup() {
            try { cleanup.get().run(); }
            catch (Throwable ignored) { /* Container lifecycle must remain no-throw. */ }
        }
        boolean closed() { return finished.get(); }
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
    @PreDestroy public void stop() {
        for (Session session : List.copyOf(sessions)) session.abort();
        replayExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }
}
